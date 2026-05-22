import { Routes } from '@angular/router';
import { RequestListComponent } from './components/request-list/request-list.component';
import { RequestDetailComponent } from './components/request-detail/request-detail.component';
import { TaskInboxComponent } from './components/task-inbox/task-inbox.component';

export const routes: Routes = [
  { path: '', component: RequestListComponent },
  { path: 'tasks', component: TaskInboxComponent },
  { path: 'requests/:id', component: RequestDetailComponent },
  { path: '**', redirectTo: '' }
];
