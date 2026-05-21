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

  /** 'connecting' covers the gap between the countdown ending and Jitsi firing 'videoConferenceJoined'. */
  state: 'idle' | 'waiting' | 'connecting' | 'joined' | 'error' = 'idle';
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
  // Authoritative state of the Jitsi mic. The recorded .webm follows this
  // directly: muted in the toolbar → silence in the file, unmuted → captured.
  // This matches the user's mental model from Zoom/Teams/Meet — the mic
  // button is the visible control of "is my audio being captured."
  private jitsiMuted = false;

  constructor(
    private interviewService: InterviewService,
    private ngZone: NgZone
  ) { }

  ngOnInit() {
    if (!this.roomUrl || !this.interviewId) { this.state = 'error'; return; }
    this.scheduleAutoStart();
    window.addEventListener('beforeunload', this.onBeforeUnload);
  }

/**
 * Single point of truth for whether the MediaRecorder's audio track is live.
 * The recording follows the Jitsi mic button: when the participant mutes
 * themselves in the toolbar, the .webm receives digital silence; when they
 * unmute, capture resumes. This matches the universal video-call mental
 * model (Zoom/Teams/Meet) and is what a jury or a real interviewer expects.
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
        startWithAudioMuted: observer,
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
        TOOLBAR_BUTTONS: observer
          ? ['hangup', 'chat']
          : ['microphone', 'camera', 'hangup', 'chat', 'desktop'],
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
      this.jitsiMuted = !!e?.muted;
      this.applyMicGating();
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

    // Enforce the initial gating: tracks default to enabled after
    // getUserMedia, but if the window isn't focused or Jitsi was started
    // muted, we want the .webm to be silent until the user explicitly
    // takes focus / unmutes.
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

  stopRecording() {
  clearTimeout(this.stopTimer);
  if (this.mediaRecorder?.state !== 'inactive') this.mediaRecorder?.stop();
  this.micStream?.getTracks().forEach(t => t.stop());
  this.ngZone.run(() => this.isRecording = false);
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