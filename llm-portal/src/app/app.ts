import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ProviderStatus } from './status/provider-status';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ProviderStatus],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {}
