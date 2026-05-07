import { Component, Input, Output, EventEmitter } from '@angular/core';
import { FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-schedule-interview',
  imports: [CommonModule,ReactiveFormsModule,FormsModule],
  templateUrl: './schedule-interview.html',
  styleUrl: './schedule-interview.css',
})
export class ScheduleInterview {
 @Input() applicationId!: string;
  @Input() jobId!: string;
  @Input() jobTitle!: string;
  @Input() candidateId!: string;
  @Input() candidateEmail!: string;
  @Input() recruiterId!: string;
  @Input() recruiterEmail!: string;

  @Output() scheduled = new EventEmitter<InterviewResponse>();

  form: FormGroup;
  loading = false;
  error = '';

  constructor(
    private fb: FormBuilder,
    private interviewService: InterviewService
  ) {
    this.form = this.fb.group({
      scheduledAt: ['', Validators.required],
      recordingConsent: [false]
    });
  }

  submit() {
    if (this.form.invalid) return;
    this.loading = true;
    this.error = '';

    this.interviewService.schedule({
      applicationId: this.applicationId,
      jobId: this.jobId,
      jobTitle: this.jobTitle,
      recruiterId: this.recruiterId,
      candidateId: this.candidateId,
      candidateEmail: this.candidateEmail,
      recruiterEmail: this.recruiterEmail,
      scheduledAt: this.form.value.scheduledAt + ':00',
      recordingConsent: this.form.value.recordingConsent
    }).subscribe({
      next: (interview) => {
        this.loading = false;
        this.scheduled.emit(interview);
      },
      error: (err) => {
        this.loading = false;
        this.error = 'Failed to schedule interview. Please try again.';
        console.error(err);
      }

    });
  }
}