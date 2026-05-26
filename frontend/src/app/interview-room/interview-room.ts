import {
  Component, Input, Output, EventEmitter, OnInit, OnDestroy,
  ElementRef, ViewChild, NgZone
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { InterviewQuestion, InterviewService } from '../services/interview-service';

interface QuestionGroup {
  category: string;
  questions: InterviewQuestion[];
}

declare const JitsiMeetExternalAPI: any;

const MAX_RECORDING_MS = 2 * 60 * 60 * 1000;

/**
 * Display-name patterns of known third-party AI notetaker bots that auto-join
 * Jitsi calls from a participant's Google Calendar. We kick them on sight
 * because:
 *   • The recording goes to a third-party SaaS (Fireflies, Otter, etc.)
 *     without VERMEG's consent — a GDPR data-processor relationship that
 *     was never set up.
 *   • HireAI already records the call via the analysis-service for the
 *     transcript pipeline. Bot-side transcription is redundant.
 *   • Recruiters wouldn't notice the bot until reviewing the participant
 *     list, by which point sensitive content is already on the bot's cloud.
 *
 * Match is case-insensitive substring. Add a token whenever a new notetaker
 * shows up — keeping this current is cheaper than auditing each one.
 */
const BOT_NAME_PATTERN = /\b(fireflies|otter\.ai|otter ai|read\.ai|read ai|fathom|noty\.ai|noty ai|tactiq|krisp|sembly|notetaker|note taker|transcribe bot|tldv|t\.l\.d\.v|meetgeek|grain\.com|chorus\.ai|jamie ai)\b/i;

@Component({
  selector: 'app-interview-room',
  imports: [CommonModule],
  templateUrl: './interview-room.html',
  styleUrl: './interview-room.css',
})
export class InterviewRoom implements OnInit, OnDestroy {
  @Input() interviewId!: string;
  @Input() participantName!: string;
  /** 'recruiter' / 'candidate' record a track; 'observer' is watch-only. */
  @Input() role: 'recruiter' | 'candidate' | 'observer' = 'candidate';
  @Input() roomUrl!: string;
  @Input() scheduledAt!: string;
  /** Shown on the pre-interview waiting card so the room has context. */
  @Input() jobTitle: string = '';
  /** Has the candidate been let into the room yet? Drives the recruiter's admit banner. */
  @Input() candidateAdmitted = false;
  /** True while the admit API call is in flight — disables the button. */
  @Input() admitting = false;
  /** Recruiter clicked "Admit" — the page handles the API call. */
  @Output() admitCandidate = new EventEmitter<void>();
  /** Jitsi reported the conference is fully closed — the page decides where to send the user. */
  @Output() interviewEnded = new EventEmitter<void>();

  @ViewChild('jitsiContainer', { static: true }) containerRef!: ElementRef;

  /**
   * 'connecting' covers the gap between the countdown ending and Jitsi
   * firing 'videoConferenceJoined'.
   * 'ending' covers the brief window between the user clicking
   * End interview and the parent navigating away — without this, Jitsi
   * flashes its "Build your video experience" promo page while we wait
   * for the upload + navigation, which looks unprofessional.
   */
  state: 'idle' | 'waiting' | 'connecting' | 'joined' | 'ending' | 'error' = 'idle';
  isRecording = false;
  uploadStatus: 'idle' | 'uploading' | 'done' | 'error' = 'idle';
  countdown = '';
  questions: InterviewQuestion[] = [];
  askedQuestions: Set<string> = new Set();
  questionsLoading = false;

  private jitsiApi: any = null;
  private mediaRecorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private micStream: MediaStream | null = null;
  private stopTimer: any = null;
  private countdownTimer: any = null;
  private joinedAt: number = 0;
  // Cached mute state. Updated on the audioMuteStatusChanged event AND polled
  // periodically — the event fires reliably only on the FIRST click that
  // actually flips the toolbar; subsequent rapid clicks (which users do when
  // the button "doesn't respond") can miss the event entirely. Public so the
  // template can bind the mic-toggle button label/icon to it.
  //
  // Initialised in ngOnInit once the @Input() role is bound — the candidate
  // and observer join MUTED, the recruiter joins UNMUTED to greet.
  jitsiMuted = false;
  private muteSyncTimer: any = null;

  constructor(
    private interviewService: InterviewService,
    private ngZone: NgZone
  ) { }

  ngOnInit() {
    if (!this.roomUrl || !this.interviewId) { this.state = 'error'; return; }
    // Initialise mute state per role: candidate and observer start muted so
    // the candidate's mic does NOT capture the recruiter's opening greeting
    // before the candidate takes their first turn. This eliminates the
    // "both tabs unmuted at start of call" leak that caused cross-mixing in
    // the very first transcript line.
    this.jitsiMuted = this.role !== 'recruiter';
    this.scheduleAutoStart();
    window.addEventListener('beforeunload', this.onBeforeUnload);
  }

/**
 * User-facing mic toggle. Driven by our own button in the recording bar
 * (we removed Jitsi's microphone toolbar button because its click handler
 * was unreliable). One click does three things atomically:
 *
 *   1. Flip our cached state — UI updates instantly
 *   2. Apply the new state to the MediaRecorder track — .webm starts
 *      capturing or going silent on the very next encoded frame
 *   3. Tell Jitsi to mirror the same state — other call participants
 *      hear or stop hearing us in line with the recording
 *
 * The 500ms polling below is now a passive safety-net that resyncs if
 * something else changes Jitsi's state out-of-band.
 */
async toggleMute() {
  // Diagnostic logging: every click should produce a paired BEFORE/AFTER
  // entry in the browser console with timestamps. If a click appears to
  // "do nothing", the entries will reveal whether (a) toggleMute was even
  // invoked, (b) the track state actually flipped, or (c) Jitsi's mirror
  // failed.
  const role = this.role;
  const t0 = performance.now().toFixed(0);
  if (!this.micStream) {
    console.warn(`[MUTE/${role}/${t0}] toggleMute: NO micStream — click ignored`);
    return;
  }
  const tracksBefore = this.micStream.getAudioTracks().map(t => t.enabled);
  const newMuted = !this.jitsiMuted;
  console.log(
    `[MUTE/${role}/${t0}] toggleMute click. wasMuted=${this.jitsiMuted} ` +
    `→ newMuted=${newMuted}. tracks.enabled before=${JSON.stringify(tracksBefore)}`,
  );
  this.jitsiMuted = newMuted;
  this.micStream.getAudioTracks().forEach(t => { t.enabled = !newMuted; });
  const tracksAfter = this.micStream.getAudioTracks().map(t => t.enabled);
  console.log(
    `[MUTE/${role}/${t0}] tracks.enabled after=${JSON.stringify(tracksAfter)} ` +
    `(expected ${!newMuted})`,
  );
  // Mirror to Jitsi so other participants' incoming audio matches what
  // the recording is doing. executeCommand is more reliable than the
  // toolbar button click — it does not depend on iframe focus or DOM
  // event delivery.
  try {
    const jitsiMuted = await this.jitsiApi?.isAudioMuted();
    console.log(
      `[MUTE/${role}/${t0}] jitsi reports muted=${jitsiMuted}. ` +
      `${jitsiMuted !== newMuted ? 'CALLING toggleAudio to sync' : 'already in sync'}`,
    );
    if (jitsiMuted !== newMuted) {
      this.jitsiApi?.executeCommand('toggleAudio');
    }
  } catch (e) {
    console.warn(`[MUTE/${role}/${t0}] jitsi mirror failed:`, e);
    // If Jitsi mirror fails the recording still respects newMuted —
    // the call audio just won't follow, which is the lesser problem.
  }
}

/**
 * Apply our cached mute state to the MediaRecorder track. Called once at
 * recording start to enforce the initial role-based mute state. The user-
 * facing path (toggleMute() above) updates the track directly without
 * going through here — so there is NO async Jitsi query that could race.
 */
private applyMicGating() {
  if (!this.micStream) return;
  const active = !this.jitsiMuted;
  this.micStream.getAudioTracks().forEach(t => { t.enabled = active; });
}

private onBeforeUnload = () => {
  if (!this.isRecording) return;

  if (this.mediaRecorder?.state === 'recording') this.mediaRecorder.requestData();
  this.micStream?.getTracks().forEach(t => t.stop());

  const role = this.role;
  const joinedAt = new Date(this.joinedAt).toISOString();
  const leftAt = new Date().toISOString();

  if (this.chunks.length > 0) {
    const form = new FormData();
    form.append('file', new Blob(this.chunks, { type: 'audio/webm' }), `${role}-recording.webm`); // ✅ fixed filename
    form.append('role', role);
    form.append('joinedAt', joinedAt);
    form.append('leftAt', leftAt);
    navigator.sendBeacon(`/api/interviews/${this.interviewId}/recording`, form);
  }

  navigator.sendBeacon(
    `/api/interviews/${this.interviewId}/left`,
    new Blob([JSON.stringify({ role })], { type: 'application/json' })
  );
};

  ngOnDestroy() {
    window.removeEventListener('beforeunload', this.onBeforeUnload);
    clearInterval(this.countdownTimer);
    clearInterval(this.muteSyncTimer);
    clearTimeout(this.stopTimer);
    if (this.isRecording) this.stopRecording();
    this.jitsiApi?.dispose();
  }

  private scheduleAutoStart() {
    const delay = new Date(this.scheduledAt).getTime() - Date.now();
    if (delay <= 0) { this.state = 'connecting'; this.loadAndJoin(); return; }
    this.state = 'waiting';
    this.updateCountdown(delay);
    this.countdownTimer = setInterval(() => {
      const remaining = new Date(this.scheduledAt).getTime() - Date.now();
      if (remaining <= 0) {
        clearInterval(this.countdownTimer);
        // Drop the wait card right away so the user isn't left staring at an empty countdown.
        this.ngZone.run(() => {
          this.countdown = '';
          this.state = 'connecting';
        });
        this.loadAndJoin();
      } else {
        this.ngZone.run(() => this.updateCountdown(remaining));
      }
    }, 1000);
  }

  private updateCountdown(ms: number) {
    const s = Math.floor(ms / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    // Big-clock format: HH:MM:SS over an hour, MM:SS otherwise.
    const pad = (n: number) => String(n).padStart(2, '0');
    this.countdown = h > 0
      ? `${pad(h)}:${pad(m)}:${pad(sec)}`
      : `${pad(m)}:${pad(sec)}`;
  }

  /** Friendly label for when the interview is scheduled. */
  get scheduledTimeLabel(): string {
    if (!this.scheduledAt) return '';
    return new Date(this.scheduledAt).toLocaleString('en-GB', {
      weekday: 'long', day: 'numeric', month: 'long',
      hour: '2-digit', minute: '2-digit',
    });
  }

  get roleLabel(): string {
    if (this.role === 'recruiter') return 'Recruiter';
    if (this.role === 'candidate') return 'Candidate';
    return 'Observer';
  }

  private loadAndJoin() {
    this.loadJitsiScript()
      .then(() => this.initJitsi())
      .catch(() => this.ngZone.run(() => this.state = 'error'));
  }

  private loadJitsiScript(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (typeof JitsiMeetExternalAPI !== 'undefined') { resolve(); return; }
      const script = document.createElement('script');
      script.src = 'https://meet.jit.si/external_api.js';
      script.onload = () => resolve();
      script.onerror = () => reject();
      document.head.appendChild(script);
    });
  }

  private initJitsi() {
    const roomName = this.roomUrl.replace('https://meet.jit.si/', '');
    const observer = this.role === 'observer';

    this.jitsiApi = new JitsiMeetExternalAPI('meet.jit.si', {
      roomName,
      parentNode: this.containerRef.nativeElement,
      width: '100%',
      height: '100%',
      userInfo: {
        // Observers are clearly labelled so the candidate/recruiter know.
        displayName: observer
          ? this.participantName + ' (Observer)'
          : this.participantName,
      },
      configOverwrite: {
        // Skip Jitsi's native "Rejoindre la réunion" pre-join page — we have our own waiting card.
        prejoinPageEnabled: false,
        prejoinConfig: { enabled: false },
        // Suppress Jitsi's "Demander à rejoindre" lobby — our app already gates admission.
        lobby: { enabled: false, autoKnock: false },
        enableLobbyChat: false,
        // Cleaner meeting title than the room slug.
        subject: this.jobTitle || 'Interview',
        // Observers join muted; the missing toolbar buttons mean they can't unmute.
        // The recruiter greets first, so they join unmuted. The candidate
        // (and observers) join MUTED so the candidate's mic doesn't capture
        // any of the recruiter's opening greeting before the candidate
        // takes their first turn — that "dual-unmute window at start of
        // call" was the source of cross-mixing in the opening turn.
        startWithAudioMuted: this.role !== 'recruiter',
        // Camera on for recruiter & candidate, off for observers.
        startWithVideoMuted: observer,
        disableDeepLinking: true,
      },
      interfaceConfigOverwrite: {
        // Strip every Jitsi-branded element we can from the in-room UI.
        SHOW_JITSI_WATERMARK: false,
        SHOW_BRAND_WATERMARK: false,
        SHOW_POWERED_BY: false,
        SHOW_PROMOTIONAL_CLOSE_PAGE: false,
        JITSI_WATERMARK_LINK: '',
        BRAND_WATERMARK_LINK: '',
        DEFAULT_LOGO_URL: '',
        DEFAULT_WELCOME_PAGE_LOGO_URL: '',
        HIDE_INVITE_MORE_HEADER: true,
        MOBILE_APP_PROMO: false,
        DISPLAY_WELCOME_PAGE_CONTENT: false,
        DISPLAY_WELCOME_FOOTER: false,
        // We remove both 'microphone' and 'hangup' for participants:
        //   - microphone: the Jitsi iframe's mute button is unreliable
        //     (clicks frequently fail to register). Our own toggleMute()
        //     below the iframe drives the MediaRecorder track AND mirrors
        //     to Jitsi via executeCommand in lockstep.
        //   - hangup: having two end-call buttons (Jitsi's red phone AND
        //     our "End & Save") was confusing. Our button now ends the
        //     call too, via executeCommand('hangup') after the upload.
        // Observers keep 'hangup' because they don't have End & Save.
        TOOLBAR_BUTTONS: observer
          ? ['hangup', 'chat']
          : ['camera', 'chat', 'desktop'],
      },
    });

    this.jitsiApi.on('videoConferenceJoined', () => {
      this.ngZone.run(() => {
        this.state = 'joined';
        this.joinedAt = Date.now();

        if (this.role === 'recruiter') {
          this.interviewService.start(this.interviewId).subscribe({
            next: () => this.interviewService.notifyChanged(), // refresh the navbar badge → "Interview live now"
            error: (e) => console.warn('Could not mark interview as started:', e)
          });
          this.loadQuestions();
        }
        // Observers never record — keeps the 2-speaker transcript clean.
        if (this.role !== 'observer') this.startRecording();
      });
    });

    // Mirror the Jitsi mic mute into our MediaRecorder. The two streams are
    // independent (Jitsi has its own getUserMedia for the call, ours is for
    // the .webm uploaded to the analysis service), so clicking the toolbar
    // mute would only silence the call audio without this bridge.
    this.jitsiApi.on('audioMuteStatusChanged', (e: { muted: boolean }) => {
      const ts = performance.now().toFixed(0);
      console.log(
        `[MUTE/${this.role}/${ts}] jitsi event audioMuteStatusChanged ` +
        `muted=${e?.muted}. (cached jitsiMuted before=${this.jitsiMuted})`,
      );
      this.jitsiMuted = !!e?.muted;
      this.applyMicGating();
    });

    // ── Bot auto-kick ─────────────────────────────────────────────────────
    // Third-party AI notetakers (Fireflies, Otter, Read.ai, etc.) auto-join
    // Jitsi calls whenever they detect a meeting link on a connected Google
    // calendar. They record + transcribe the conversation onto their cloud
    // without anyone in the room consenting. For a hiring platform that's
    // an immediate privacy + GDPR problem (the candidate didn't sign a DPA
    // with Fireflies, and neither did VERMEG).
    //
    // The recruiter is the moderator (first to join → gets moderator rights
    // automatically on public Jitsi). We listen for any participant whose
    // display name matches a known bot regex and kick them on sight. The
    // candidate-side runs this code too but their kick attempts no-op
    // silently (they aren't the moderator), so it's safe to leave on for
    // every role.
    this.jitsiApi.on('participantJoined', (e: { id: string; displayName?: string }) => {
      if (!e?.id) return;
      const name = (e.displayName || '').toLowerCase();
      if (BOT_NAME_PATTERN.test(name)) {
        // Tiny delay so Jitsi has fully registered the participant before we
        // try to remove them — kicking inside the same tick sometimes 404s.
        setTimeout(() => {
          try {
            this.jitsiApi?.executeCommand('kickParticipant', e.id);
            console.warn(`[interview-room] Auto-kicked notetaker bot "${e.displayName}" (id=${e.id})`);
          } catch (err) {
            console.warn('[interview-room] Failed to kick bot:', err);
          }
        }, 250);
      }
    });

    this.jitsiApi.on('videoConferenceLeft', () => {
      this.ngZone.run(() => { if (this.isRecording) this.stopRecording(); });
    });

    this.jitsiApi.on('readyToClose', () => {
      this.ngZone.run(() => {
        if (this.isRecording) this.stopRecording();
        // Tell the page the call is over — it'll mark the interview complete and route away.
        this.interviewEnded.emit();
      });
    });
  }

  // ── Recording ─────────────────────────────────────────────────────────────

  async startRecording() {
  if (this.role === 'observer') return;   // observers record nothing
  try {
    this.micStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
      video: false,
    });

    // Apply the initial mute state — we don't poll continuously anymore
    // because our toggleMute() button is the SOLE control of the mic now
    // (Jitsi's flaky button was removed from the toolbar). A periodic
    // poll racing with our own click handler caused a ~100ms window where
    // the click was registered locally but Jitsi hadn't finished
    // processing the matching executeCommand, so polling read a stale
    // "not muted" value and undid the mute, leaking audio.
    this.applyMicGating();

    // Each side records only its own mic, under its own role.
    this.startSingleTrackRecording(this.micStream, this.role);

  } catch (err) {
    console.error('Recording setup failed', err);
    this.ngZone.run(() => this.state = 'error');
  }
}

  private startSingleTrackRecording(stream: MediaStream, role: string) {
    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus' : 'audio/webm';

    this.chunks = [];
    this.mediaRecorder = new MediaRecorder(stream, { mimeType });
    this.mediaRecorder.ondataavailable = e => { if (e.data.size > 0) this.chunks.push(e.data); };
    this.mediaRecorder.onstop = () => this.uploadBlob(this.chunks, role);
    this.mediaRecorder.start(3000);

    this.ngZone.run(() => this.isRecording = true);
    this.stopTimer = setTimeout(() => this.ngZone.run(() => this.stopRecording()), MAX_RECORDING_MS);
    console.log(`Single-track recording started for ${role}`);
  }

  /**
   * End the interview. Single user-facing action that replaces the old
   * "click End & Save then also click Jitsi's hangup" two-step:
   *   1. Stop and upload the recording.
   *   2. Tell Jitsi to leave the room. Jitsi's `readyToClose` listener
   *      then fires interviewEnded so the page navigates away.
   * Safe to call from the recording timeout, the manual button, the
   * Jitsi hangup event handler — all converge to one path.
   */
  stopRecording() {
    clearTimeout(this.stopTimer);
    if (this.mediaRecorder?.state !== 'inactive') this.mediaRecorder?.stop();
    this.micStream?.getTracks().forEach(t => t.stop());
    this.ngZone.run(() => {
      this.isRecording = false;
      // Flip to 'ending' so the template hides the Jitsi iframe and shows
      // our own "Wrapping up" card. Without this, Jitsi flashes a 8x8
      // promo page during the ~800ms window before navigation.
      this.state = 'ending';
    });
    // Leave the Jitsi call.
    try { this.jitsiApi?.executeCommand('hangup'); } catch { /* no-op */ }
    // Tell the parent the interview is over. We do NOT rely on Jitsi's
    // `readyToClose` event for this — Jitsi only fires it consistently
    // when the user clicks its toolbar hangup button, NOT when hangup is
    // invoked via executeCommand. Skipping this emit was the bug where
    // "End interview" appeared to do nothing — the recording stopped but
    // the page never navigated away.
    this.interviewEnded.emit();
  }

  private uploadBlob(chunks: Blob[], role: string) {
    if (chunks.length === 0) { console.warn(`No chunks for ${role} — skipping upload`); return; }

    const blob     = new Blob(chunks, { type: 'audio/webm' });
    const file     = new File([blob], `${role}-recording.webm`, { type: 'audio/webm' });
    const joinedAt = new Date(this.joinedAt).toISOString();
    const leftAt   = new Date().toISOString();

    console.log(`Uploading ${role} blob: ${blob.size} bytes`);
    this.ngZone.run(() => this.uploadStatus = 'uploading');

    this.interviewService.uploadRecording(this.interviewId, file, role, joinedAt, leftAt).subscribe({
      next: () => {
        console.log(`Upload success: ${role}`);
        this.ngZone.run(() => this.uploadStatus = 'done');
        this.notifyLeft();
      },
      error: (err) => {
        console.error(`Upload failed for ${role}:`, err);
        // Beacon fallback
        const form = new FormData();
        form.append('file', blob, `${role}-recording.webm`);
        form.append('role', role);
        form.append('joinedAt', joinedAt);
        form.append('leftAt', leftAt);
        const sent = navigator.sendBeacon(`/api/interviews/${this.interviewId}/recording`, form);
        this.ngZone.run(() => this.uploadStatus = sent ? 'done' : 'error');
        if (sent) this.notifyLeft();
      }
    });
  }

  private notifyLeft() {
    this.interviewService
      .notifyLeft(this.interviewId, this.role)
      .subscribe({ error: (e) => console.warn('notifyLeft failed:', e) });
  }

  // ── Questions ─────────────────────────────────────────────────────────────

  get groupedQuestions(): QuestionGroup[] {
    const order: Array<InterviewQuestion['category']> = ['technical', 'cv_specific', 'behavioral'];
    const labels: Record<string, string> = {
      technical:   'Technical',
      cv_specific: 'CV-Specific',
      behavioral:  'Behavioral',
    };
    return order
      .map(cat => ({
        category: labels[cat],
        questions: this.questions.filter(q => q.category === cat),
      }))
      .filter(g => g.questions.length > 0);
  }

  loadQuestions() {
    if (this.role !== 'recruiter') return;
    this.questionsLoading = true;
    this.interviewService.getQuestions(this.interviewId).subscribe({
      next: (q) => this.ngZone.run(() => { this.questions = q; this.questionsLoading = false; }),
      error: ()  => { console.warn('Could not load questions'); this.ngZone.run(() => this.questionsLoading = false); }
    });
  }

  markQuestion(questionId: string, status: 'ASKED' | 'SKIPPED') {
    this.interviewService.markQuestion(this.interviewId, questionId, status).subscribe();
    this.ngZone.run(() => this.askedQuestions.add(questionId));
  }
}