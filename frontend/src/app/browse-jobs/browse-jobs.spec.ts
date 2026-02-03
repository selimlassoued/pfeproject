import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BrowseJobs } from './browse-jobs';

describe('BrowseJobs', () => {
  let component: BrowseJobs;
  let fixture: ComponentFixture<BrowseJobs>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BrowseJobs]
    })
    .compileComponents();

    fixture = TestBed.createComponent(BrowseJobs);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
