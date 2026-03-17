import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CvAnalysisDrawer } from './cv-analysis-drawer';

describe('CvAnalysisDrawer', () => {
  let component: CvAnalysisDrawer;
  let fixture: ComponentFixture<CvAnalysisDrawer>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CvAnalysisDrawer]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CvAnalysisDrawer);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
