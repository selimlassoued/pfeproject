import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ListApplications } from './list-applications';

describe('ListApplications', () => {
  let component: ListApplications;
  let fixture: ComponentFixture<ListApplications>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ListApplications]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ListApplications);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
