import { Routes } from '@angular/router';
import { Test } from './test/test';
import { canActivateAuthRole } from './guards/auth-role.guard';
import { Forbidden } from './forbidden/forbidden';
import { Profile } from './profile/profile';

export const routes: Routes = [

    {path: "test", 
    component : Test, 
    canActivate: [canActivateAuthRole],    
    data: { role: 'ADMIN' }
    },
     { path: 'profile', component: Profile },
    { path: 'forbidden', component: Forbidden }

];