import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

interface JobPreview {
  title: string;
  location: string;
  type: string;
  status: string;
  tags: string[];
}

@Component({
  selector: 'app-hero',
  imports: [CommonModule],
  templateUrl: './hero.html',
  styleUrl: './hero.css',
})
export class Hero {
  jobs: JobPreview[] = [
    {
      title: 'Software Engineer Intern',
      location: 'Remote',
      type: 'Internship',
      status: 'New',
      tags: ['Java', 'Spring Boot', 'SQL'],
    },
    {
      title: 'QA Automation Engineer',
      location: 'Hybrid',
      type: 'Full-time',
      status: 'Open',
      tags: ['Selenium', 'API testing', 'CI/CD'],
    },
    {
      title: 'Business Analyst',
      location: 'On-site',
      type: 'Full-time',
      status: 'Open',
      tags: ['Requirements', 'Stakeholders', 'Documentation'],
    },
  ];

  scrollToCandidate() {
    document.getElementById('candidate')?.scrollIntoView({ behavior: 'smooth' });
  }
}
