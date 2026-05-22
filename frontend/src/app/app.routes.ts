import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./components/request-list/request-list').then(m => m.RequestList)
  },
  {
    path: 'tasks',
    loadComponent: () => import('./components/task-inbox/task-inbox').then(m => m.TaskInbox)
  },
  {
    path: 'requests/:id',
    loadComponent: () => import('./components/request-detail/request-detail').then(m => m.RequestDetail)
  },
  { path: '**', redirectTo: '' }
];
