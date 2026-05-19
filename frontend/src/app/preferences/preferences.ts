import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CandidateProfileService, CandidateLanguage } from '../services/candidate-profile.service';

const DOMAIN_SKILLS: Record<string, string[]> = {
  SOFTWARE_ENGINEERING: [
    'Java', 'Spring Boot', 'Angular', 'React', 'Vue.js', 'Python', 'Node.js',
    'TypeScript', 'JavaScript', 'Docker', 'Kubernetes', 'PostgreSQL', 'MongoDB',
    'SQL', 'Git', 'REST API', 'GraphQL', 'Microservices', 'CI/CD', 'Jenkins',
    'AWS', 'Azure', 'Linux', 'C++', 'C#', '.NET', 'Hibernate', 'Redis',
  ],
  FINANCE_BANKING: [
    'Financial Analysis', 'Risk Management', 'Excel', 'SAP', 'Bloomberg', 'SWIFT',
    'Accounting', 'Audit', 'Basel III', 'Anti-Money Laundering', 'Treasury Management',
    'Financial Modeling', 'Power BI', 'VBA', 'SQL',
  ],
  INSURANCE: [
    'Actuarial Analysis', 'Claims Management', 'Policy Administration', 'Underwriting',
    'Reinsurance', 'Solvency II', 'Insurance Software', 'Risk Assessment', 'Excel',
  ],
  PROJECT_MANAGEMENT: [
    'Agile', 'Scrum', 'PMP', 'PRINCE2', 'JIRA', 'MS Project', 'Risk Management',
    'Stakeholder Management', 'Waterfall', 'Kanban', 'Confluence', 'SAFe',
  ],
  QUALITY_ASSURANCE: [
    'Manual Testing', 'Selenium', 'JUnit', 'Test Automation', 'JIRA', 'Postman',
    'Load Testing', 'JMeter', 'SoapUI', 'API Testing', 'Cucumber', 'TestNG',
  ],
  BUSINESS_ANALYSIS: [
    'Requirements Analysis', 'UML', 'BPMN', 'Use Cases', 'Wireframing', 'SQL',
    'Stakeholder Management', 'Process Modeling', 'Agile', 'JIRA', 'Confluence', 'Power BI',
  ],
};

const SOFT_SKILLS = [
  'Communication', 'Leadership', 'Teamwork', 'Problem Solving', 'Time Management',
  'Adaptability', 'Critical Thinking', 'Creativity', 'Attention to Detail',
  'Conflict Resolution', 'Negotiation', 'Presentation Skills', 'Emotional Intelligence',
  'Decision Making', 'Autonomy',
];

@Component({
  selector: 'app-preferences',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule],
  templateUrl: './preferences.html',
  styleUrls: ['./preferences.css'],
})
export class Preferences implements OnInit {
  loading = true;
  saving  = false;
  error?: string;
  success?: string;

  form: FormGroup;

  readonly domains = [
    { value: 'SOFTWARE_ENGINEERING', label: 'Software Engineering / IT' },
    { value: 'FINANCE_BANKING',      label: 'Finance & Banking' },
    { value: 'INSURANCE',            label: 'Insurance' },
    { value: 'PROJECT_MANAGEMENT',   label: 'Project Management' },
    { value: 'QUALITY_ASSURANCE',    label: 'Quality Assurance / Testing' },
    { value: 'BUSINESS_ANALYSIS',    label: 'Business Analysis' },
  ];

  readonly softSkills = SOFT_SKILLS;

  selectedHardSkills: string[] = [];
  selectedSoftSkills: string[] = [];
  languages: CandidateLanguage[] = [];

  readonly cefrLevels = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'];
  readonly commonLanguages = ['Arabic', 'French', 'English', 'German', 'Spanish', 'Italian', 'Mandarin'];
  newLanguage = '';
  newLevel = 'B1';

  constructor(
    private fb: FormBuilder,
    private profileService: CandidateProfileService,
    private router: Router,
  ) {
    this.form = this.fb.group({
      status:                   [''],
      yearsOfExperience:        [''],
      educationLevel:           [''],
      domain:                   [''],
      preferredWorkArrangement: [''],
      preferredJobType:         [''],
    });
  }

  async ngOnInit() { await this.load(); }

  async load() {
    this.loading = true;
    this.error   = undefined;
    try {
      const p = await this.profileService.get();
      this.form.patchValue({
        status:                   p.status                   ?? '',
        yearsOfExperience:        p.yearsOfExperience        ?? '',
        educationLevel:           p.educationLevel           ?? '',
        domain:                   p.domain                   ?? '',
        preferredWorkArrangement: p.preferredWorkArrangement ?? '',
        preferredJobType:         p.preferredJobType         ?? '',
      });
      this.selectedHardSkills = p.hardSkills ?? [];
      this.selectedSoftSkills = p.softSkills ?? [];
      this.languages = p.languages ?? [];
    } catch {
      this.error = 'Failed to load preferences';
    } finally {
      this.loading = false;
    }
  }

  get availableHardSkills(): string[] {
    return DOMAIN_SKILLS[this.form.get('domain')?.value ?? ''] ?? [];
  }

  hardSkillRank(skill: string): number | null {
    const idx = this.selectedHardSkills.indexOf(skill);
    return idx === -1 ? null : idx + 1;
  }

  softSkillRank(skill: string): number | null {
    const idx = this.selectedSoftSkills.indexOf(skill);
    return idx === -1 ? null : idx + 1;
  }

  toggleHardSkill(skill: string) {
    const idx = this.selectedHardSkills.indexOf(skill);
    this.selectedHardSkills = idx === -1
      ? [...this.selectedHardSkills, skill]
      : this.selectedHardSkills.filter(s => s !== skill);
  }

  toggleSoftSkill(skill: string) {
    const idx = this.selectedSoftSkills.indexOf(skill);
    this.selectedSoftSkills = idx === -1
      ? [...this.selectedSoftSkills, skill]
      : this.selectedSoftSkills.filter(s => s !== skill);
  }

  onDomainChange() { this.selectedHardSkills = []; }

  addLanguage() {
    const lang = this.newLanguage.trim();
    if (!lang || this.languages.some(l => l.language.toLowerCase() === lang.toLowerCase())) return;
    this.languages = [...this.languages, { language: lang, level: this.newLevel }];
    this.newLanguage = '';
    this.newLevel = 'B1';
  }

  removeLanguage(index: number) {
    this.languages = this.languages.filter((_, i) => i !== index);
  }

  updateLanguageLevel(index: number, level: string) {
    this.languages = this.languages.map((l, i) => i === index ? { ...l, level } : l);
  }

  setDomain(v: string)          { this.form.get('domain')?.setValue(v); this.onDomainChange(); }
  setWorkArrangement(v: string) { this.form.get('preferredWorkArrangement')?.setValue(v); }
  setJobType(v: string)         { this.form.get('preferredJobType')?.setValue(v); }

  async save() {
    this.saving  = true;
    this.error   = undefined;
    this.success  = undefined;
    try {
      const raw = this.form.getRawValue();
      await this.profileService.save({
        status:                   raw.status                   || undefined,
        yearsOfExperience:        raw.yearsOfExperience        || undefined,
        educationLevel:           raw.educationLevel           || undefined,
        domain:                   raw.domain                   || undefined,
        hardSkills:               this.selectedHardSkills,
        softSkills:               this.selectedSoftSkills,
        languages:                this.languages,
        preferredWorkArrangement: raw.preferredWorkArrangement || undefined,
        preferredJobType:         raw.preferredJobType         || undefined,
      });
      this.success = 'Preferences saved successfully.';
    } catch {
      this.error = 'Failed to save preferences.';
    } finally {
      this.saving = false;
    }
  }

  goBack() { this.router.navigate(['/profile']); }
}
