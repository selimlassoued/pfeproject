import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InterviewResult } from './interview-result';

describe('InterviewResult', () => {
  let component: InterviewResult;
  let fixture: ComponentFixture<InterviewResult>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InterviewResult]
    })
    .compileComponents();

    fixture = TestBed.createComponent(InterviewResult);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
