import { Routes } from '@angular/router';
import { RequestListComponent } from './components/request-list/request-list.component';
import { RequestDetailComponent } from './components/request-detail/request-detail.component';

export const routes: Routes = [
  { path: '', component: RequestListComponent },
  { path: 'requests/:id', component: RequestDetailComponent },
  { path: '**', redirectTo: '' }
];
