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
import { CvAnalysisDrawer } from './cv-analysis-drawer/cv-analysis-drawer';
import { homeGuard } from './guards/homeGuard';
import { InterviewList } from './interview-list/interview-list';
import { InterviewCalendar } from './interview-calendar/interview-calendar';
import { InterviewRoomPage } from './interview-room-page/interview-room-page';
import { InterviewResult } from './interview-result/interview-result';
import { Preferences } from './preferences/preferences';
import { Onboarding } from './onboarding/onboarding';
import { SkillsCatalog } from './skills-catalog/skills-catalog';
import { GoogleCallback } from './google-callback/google-callback';
import { InterviewEvaluation } from './interview-evaluation/interview-evaluation';
import { ApplicationSummary } from './application-summary/application-summary';

export const routes: Routes = [
  { path: '',             component: Hero,               canActivate: [homeGuard] },
  { path: 'hero',         component: Hero },
  { path: 'profile',      component: Profile },
  { path: 'preferences',  component: Preferences,        canActivate: [canActivateAuthRole], data: { role: 'CANDIDATE' } },
  { path: 'onboarding',  component: Onboarding,         canActivate: [canActivateAuthRole], data: { role: 'CANDIDATE' } },
  { path: 'forbidden',    component: Forbidden },
  { path: 'browse',       component: BrowseJobsComponent },
  { path: 'jobs/:id',     component: JobDetails },
  { path: 'notification-menu', component: NotificationsMenu },
  { path: 'cv-analysis',  component: CvAnalysisDrawer },
  { path: 'interviews',   component: InterviewList },
  { path: 'interview/:id/room',   component: InterviewRoomPage },
  { path: 'join/:id',             component: InterviewRoomPage },
  { path: 'interview/:id/result', component: InterviewResult },
  { path: 'interview/:id/evaluation', component: InterviewEvaluation },
  { path: 'test', component: Test, canActivate: [canActivateAuthRole], data: { role: 'ADMIN' } },

  // ── ADMIN + SUPERADMIN + RECRUITER ────────────────────────────────────────
  { path: 'listUsers',       component: ListUsers,       canActivate: [canActivateAuthRole], data: { allowedRoles: ['ADMIN', 'SUPERADMIN', 'RECRUITER'] } },
  { path: 'user/:id',        component: UserDetails,     canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'listApplications',component: ListApplications,canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'application/:id', component: ApplicationDetail,canActivate: [canActivateAuthRole],data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'application/:id/summary', component: ApplicationSummary, canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'add-job',         component: AddJob,          canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'edit-job/:id',    component: UpdateJob,       canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'admin-dashboard', component: AdminDashboard,  canActivate: [canActivateAuthRole], data: { allowedRoles: ['ADMIN', 'SUPERADMIN', 'RECRUITER'] } },
  { path: 'admin/skills-catalog', component: SkillsCatalog, canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'calendar',        component: InterviewCalendar, canActivate: [canActivateAuthRole], data: { allowedRoles: ['RECRUITER', 'ADMIN', 'SUPERADMIN'] } },
  { path: 'google-callback', component: GoogleCallback },
  { path: 'recruiter-activity', component: RecruiterActivity, canActivate: [canActivateAuthRole], data: { allowedRoles: ['ADMIN', 'SUPERADMIN'] } },
  { path: 'action-history',  component: ActionHistory,   canActivate: [canActivateAuthRole], data: { allowedRoles: ['ADMIN', 'SUPERADMIN'] } },

  // ── CANDIDATE only ────────────────────────────────────────────────────────
  { path: 'apply/:jobId',     component: Application,       canActivate: [canActivateAuthRole], data: { role: 'CANDIDATE' } },
  { path: 'my-applications',  component: MyApplications,    canActivate: [canActivateAuthRole], data: { role: 'CANDIDATE' } },
  { path: 'my-application/:id', component: MyApplicationDetail, canActivate: [canActivateAuthRole], data: { role: 'CANDIDATE' } },
];
