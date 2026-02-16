import { ComponentFixture, TestBed } from '@angular/core/testing';

import { UpdateJob } from './update-job';

describe('UpdateJob', () => {
  let component: UpdateJob;
  let fixture: ComponentFixture<UpdateJob>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UpdateJob]
    })
    .compileComponents();

    fixture = TestBed.createComponent(UpdateJob);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
