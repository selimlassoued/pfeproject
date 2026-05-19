import {
  Component, Input, OnInit, OnDestroy,
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
  @Input() isRecruiter = false;
  @Input() roomUrl!: string;
  @Input() scheduledAt!: string;

  @ViewChild('jitsiContainer', { static: true }) containerRef!: ElementRef;

  state: 'idle' | 'waiting' | 'joined' | 'error' = 'idle';
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

  constructor(
    private interviewService: InterviewService,
    private ngZone: NgZone
  ) { }

  ngOnInit() {
    if (!this.roomUrl || !this.interviewId) { this.state = 'error'; return; }
    this.scheduleAutoStart();
    window.addEventListener('beforeunload', this.onBeforeUnload);
  }

private onBeforeUnload = () => {
  if (!this.isRecording) return;

  if (this.mediaRecorder?.state === 'recording') this.mediaRecorder.requestData();
  this.micStream?.getTracks().forEach(t => t.stop());

  const role = this.isRecruiter ? 'recruiter' : 'candidate';
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
    if (delay <= 0) { this.loadAndJoin(); return; }
    this.state = 'waiting';
    this.updateCountdown(delay);
    this.countdownTimer = setInterval(() => {
      const remaining = new Date(this.scheduledAt).getTime() - Date.now();
      if (remaining <= 0) {
        clearInterval(this.countdownTimer);
        this.ngZone.run(() => this.countdown = '');
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
    this.countdown = h > 0
      ? `starts in ${h}h ${m}m ${sec}s`
      : `starts in ${m}m ${sec}s`;
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

    this.jitsiApi = new JitsiMeetExternalAPI('meet.jit.si', {
      roomName,
      parentNode: this.containerRef.nativeElement,
      width: '100%',
      height: '100%',
      userInfo: { displayName: this.participantName },
      configOverwrite: {
        startWithAudioMuted: false,
        startWithVideoMuted: true,
        disableDeepLinking: true,
      },
      interfaceConfigOverwrite: {
        SHOW_JITSI_WATERMARK: false,
        TOOLBAR_BUTTONS: ['microphone', 'camera', 'hangup', 'chat', 'desktop'],
      },
    });

    this.jitsiApi.on('videoConferenceJoined', () => {
      this.ngZone.run(() => {
        this.state = 'joined';
        this.joinedAt = Date.now();

        if (this.isRecruiter) {
          this.interviewService.start(this.interviewId).subscribe({
            error: (e) => console.warn('Could not mark interview as started:', e)
          });
          this.loadQuestions();
        }
        this.startRecording();
      });
    });

    this.jitsiApi.on('videoConferenceLeft', () => {
      this.ngZone.run(() => { if (this.isRecording) this.stopRecording(); });
    });

    this.jitsiApi.on('readyToClose', () => {
      this.ngZone.run(() => { if (this.isRecording) this.stopRecording(); });
    });
  }

  // ── Recording ─────────────────────────────────────────────────────────────

  async startRecording() {
  try {
    this.micStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
      video: false,
    });

    // Both roles now record only their own mic
    const role = this.isRecruiter ? 'recruiter' : 'candidate';
    this.startSingleTrackRecording(this.micStream, role);

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
      .notifyLeft(this.interviewId, this.isRecruiter ? 'recruiter' : 'candidate')
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
    if (!this.isRecruiter) return;
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