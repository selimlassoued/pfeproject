import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ScheduleInterview } from './schedule-interview';

describe('ScheduleInterview', () => {
  let component: ScheduleInterview;
  let fixture: ComponentFixture<ScheduleInterview>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ScheduleInterview]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ScheduleInterview);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
