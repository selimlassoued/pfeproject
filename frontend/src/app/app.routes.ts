import { Routes } from '@angular/router';
import { Test } from './test/test';
import { canActivateAuthRole } from './guards/auth-role.guard';
import { Forbidden } from './forbidden/forbidden';
import { Profile } from './profile/profile';
import { Hero } from './hero/hero';
import { BrowseJobsComponent } from './browse-jobs/browse-jobs';
import { JobDetails } from './job-details/job-details';
import { ListUsers } from './list-users/list-users';
import { UserDetails } from './user-details/user-details';
import { Application } from './application/application';
import { ListApplications } from './list-applications/list-applications';
import { ApplicationDetail } from './application-detail/application-detail';
import { AddJob } from './add-job/add-job';
import { UpdateJob } from './update-job/update-job';
import { MyApplications } from './my-applications/my-applications';
import { MyApplicationDetail } from './my-application-detail/my-application-detail';
import { NotificationsMenu } from './notification-menu/notification-menu';
import { AdminDashboard } from './admin-dashboard/admin-dashboard';
import { RecruiterActivity } from './recruiter-activity/recruiter-activity';
import { ActionHistory } from './action-history/action-history';

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
  { path: 'browse', component: BrowseJobsComponent },
  { path: 'jobs/:id', component: JobDetails },
  {path:'listUsers',component:ListUsers,canActivate: [canActivateAuthRole],data: { role: 'ADMIN' }},
  {path:'user/:id',component:UserDetails,canActivate: [canActivateAuthRole],data: { role: 'ADMIN' }},
  { path: 'apply/:jobId', component: Application,canActivate: [canActivateAuthRole],data: { role: 'CANDIDATE' } },
  {path:'listApplications',component:ListApplications,canActivate: [canActivateAuthRole],data: { allowedRoles: ['RECRUITER', 'ADMIN'] }},
  {path:'application/:id',component:ApplicationDetail,canActivate: [canActivateAuthRole],data: { allowedRoles: ['RECRUITER', 'ADMIN'] }},
  {path:'add-job', component:AddJob, canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN'] }},
  {path:'edit-job/:id', component:UpdateJob, canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN'] }},
  { path: 'my-applications', component: MyApplications,canActivate: [canActivateAuthRole],data: { role: 'CANDIDATE' } },
  {path:'my-application/:id',component:MyApplicationDetail,canActivate: [canActivateAuthRole],data: { role: 'CANDIDATE' }},
  {path:'notification-menu',component:NotificationsMenu},
  { path: 'admin-dashboard', component: AdminDashboard },
  {path:'recruiter-activity',component:RecruiterActivity},
  {path:'action-history',component:ActionHistory}



];