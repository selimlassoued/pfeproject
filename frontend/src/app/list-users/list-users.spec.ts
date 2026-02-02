import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ListUsers } from './list-users';

describe('ListUsers', () => {
  let component: ListUsers;
  let fixture: ComponentFixture<ListUsers>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ListUsers]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ListUsers);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
