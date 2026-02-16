import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Application } from './application';

describe('Application', () => {
  let component: Application;
  let fixture: ComponentFixture<Application>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Application]
    })
    .compileComponents();

    fixture = TestBed.createComponent(Application);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
