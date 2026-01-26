import { Routes } from '@angular/router';
import { Test } from './test/test';
import { canActivateAuthRole } from './guards/auth-role.guard';
import { Forbidden } from './forbidden/forbidden';
import { Profile } from './profile/profile';
import { Hero } from './hero/hero';

export const routes: Routes = [
  //{ path: 'profile', redirectTo: 'profile', pathMatch: 'full' }, 

  {
    path: 'test',
    component: Test,
    canActivate: [canActivateAuthRole],
    data: { role: 'ADMIN' },
  },
  { path: 'profile', component: Profile },
  { path: 'forbidden', component: Forbidden },

  //{ path: '**', redirectTo: 'profile' }, 
  { path: '', component: Hero },
];