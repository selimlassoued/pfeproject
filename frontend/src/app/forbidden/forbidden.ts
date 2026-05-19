import { Component } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Location } from '@angular/common';

@Component({
  selector: 'app-forbidden',
  imports: [RouterModule],
  templateUrl: './forbidden.html',
  styleUrl: './forbidden.css',
})
export class Forbidden {
  constructor(private location: Location) {}

  /** Return to the previous page in the browser history. */
  goBack(): void {
    this.location.back();
  }
}
