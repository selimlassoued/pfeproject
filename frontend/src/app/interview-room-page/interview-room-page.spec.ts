import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InterviewRoomPage } from './interview-room-page';

describe('InterviewRoomPage', () => {
  let component: InterviewRoomPage;
  let fixture: ComponentFixture<InterviewRoomPage>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InterviewRoomPage]
    })
    .compileComponents();

    fixture = TestBed.createComponent(InterviewRoomPage);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
