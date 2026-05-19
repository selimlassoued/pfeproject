import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, FormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { KeycloakAccountService } from '../services/keycloak-account-service';
import { CandidateProfileService, CandidateLanguage } from '../services/candidate-profile.service';

const DOMAIN_SKILLS: Record<string, string[]> = {
  SOFTWARE_ENGINEERING: ['Java','Spring Boot','Angular','React','Vue.js','Python','Node.js','TypeScript','JavaScript','Docker','Kubernetes','PostgreSQL','MongoDB','SQL','Git','REST API','GraphQL','Microservices','CI/CD','Jenkins','AWS','Azure','Linux','C++','C#','.NET','Hibernate','Redis'],
  FINANCE_BANKING: ['Financial Analysis','Risk Management','Excel','SAP','Bloomberg','SWIFT','Accounting','Audit','Basel III','Anti-Money Laundering','Treasury Management','Financial Modeling','Power BI','VBA','SQL'],
  INSURANCE: ['Actuarial Analysis','Claims Management','Policy Administration','Underwriting','Reinsurance','Solvency II','Insurance Software','Risk Assessment','Excel'],
  PROJECT_MANAGEMENT: ['Agile','Scrum','PMP','PRINCE2','JIRA','MS Project','Risk Management','Stakeholder Management','Waterfall','Kanban','Confluence','SAFe'],
  QUALITY_ASSURANCE: ['Manual Testing','Selenium','JUnit','Test Automation','JIRA','Postman','Load Testing','JMeter','SoapUI','API Testing','Cucumber','TestNG'],
  BUSINESS_ANALYSIS: ['Requirements Analysis','UML','BPMN','Use Cases','Wireframing','SQL','Stakeholder Management','Process Modeling','Agile','JIRA','Confluence','Power BI'],
};

const SOFT_SKILLS = ['Communication','Leadership','Teamwork','Problem Solving','Time Management','Adaptability','Critical Thinking','Creativity','Attention to Detail','Conflict Resolution','Negotiation','Presentation Skills','Emotional Intelligence','Decision Making','Autonomy'];

@Component({
  selector: 'app-onboarding',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule],
  templateUrl: './onboarding.html',
  styleUrls: ['./onboarding.css'],
})
export class Onboarding implements OnInit {
  private readonly keycloak = inject(Keycloak);

  step = 1;
  totalSteps = 5;
  saving = false;
  error?: string;

  // Step 1 form
  form: FormGroup;

  // Step 2 — domain
  readonly domains = [
    { value: 'SOFTWARE_ENGINEERING', label: 'Software Engineering / IT' },
    { value: 'FINANCE_BANKING',      label: 'Finance & Banking' },
    { value: 'INSURANCE',            label: 'Insurance' },
    { value: 'PROJECT_MANAGEMENT',   label: 'Project Management' },
    { value: 'QUALITY_ASSURANCE',    label: 'Quality Assurance / Testing' },
    { value: 'BUSINESS_ANALYSIS',    label: 'Business Analysis' },
  ];

  // Step 3 — skills
  readonly softSkills = SOFT_SKILLS;
  selectedHardSkills: string[] = [];
  selectedSoftSkills: string[] = [];

  // Step 4 — languages
  languages: CandidateLanguage[] = [];
  readonly cefrLevels = ['A1','A2','B1','B2','C1','C2'];
  readonly commonLanguages = ['Arabic','French','English','German','Spanish','Italian','Mandarin'];
  newLanguage = '';
  newLevel = 'B1';

  firstName = '';

  constructor(
    private fb: FormBuilder,
    private accountService: KeycloakAccountService,
    private profileService: CandidateProfileService,
    private router: Router,
  ) {
    this.form = this.fb.group({
      status:                   ['', Validators.required],
      yearsOfExperience:        ['', Validators.required],
      educationLevel:           ['', Validators.required],
      domain:                   [''],
      preferredWorkArrangement: [''],
      preferredJobType:         [''],
    });
  }

  async ngOnInit() {
    const profile = this.keycloak.tokenParsed;
    this.firstName = profile?.['given_name'] ?? profile?.['preferred_username'] ?? 'there';
  }

  // ── Step helpers ──────────────────────────────────────────────────────────

  get stepTitle(): string {
    switch (this.step) {
      case 1: return 'Your background';
      case 2: return 'Your domain';
      case 3: return 'Your skills';
      case 4: return 'Languages';
      case 5: return 'Job preferences';
      default: return '';
    }
  }

  get canNext(): boolean {
    if (this.step === 1) return this.form.get('status')!.valid && this.form.get('yearsOfExperience')!.valid && this.form.get('educationLevel')!.valid;
    return true;
  }

  next() { if (this.step < this.totalSteps) this.step++; }
  back() { if (this.step > 1) this.step--; }

  // ── Domain & skills ───────────────────────────────────────────────────────

  get availableHardSkills(): string[] {
    return DOMAIN_SKILLS[this.form.get('domain')?.value ?? ''] ?? [];
  }

  setDomain(v: string) {
    this.form.get('domain')?.setValue(v);
    this.selectedHardSkills = [];
  }

  isHardSelected(skill: string): boolean { return this.selectedHardSkills.includes(skill); }
  isSoftSelected(skill: string): boolean { return this.selectedSoftSkills.includes(skill); }

  toggleHard(skill: string) {
    this.selectedHardSkills = this.isHardSelected(skill)
      ? this.selectedHardSkills.filter(s => s !== skill)
      : [...this.selectedHardSkills, skill];
  }

  toggleSoft(skill: string) {
    this.selectedSoftSkills = this.isSoftSelected(skill)
      ? this.selectedSoftSkills.filter(s => s !== skill)
      : [...this.selectedSoftSkills, skill];
  }

  // ── Languages ─────────────────────────────────────────────────────────────

  addLanguage() {
    const lang = this.newLanguage.trim();
    if (!lang || this.languages.some(l => l.language.toLowerCase() === lang.toLowerCase())) return;
    this.languages = [...this.languages, { language: lang, level: this.newLevel }];
    this.newLanguage = '';
    this.newLevel = 'B1';
  }

  removeLanguage(i: number) { this.languages = this.languages.filter((_, idx) => idx !== i); }

  updateLevel(i: number, level: string) {
    this.languages = this.languages.map((l, idx) => idx === i ? { ...l, level } : l);
  }

  // ── Finish ────────────────────────────────────────────────────────────────

  setWorkArrangement(v: string) { this.form.get('preferredWorkArrangement')?.setValue(v); }
  setJobType(v: string)         { this.form.get('preferredJobType')?.setValue(v); }

  async finish() {
    this.saving = true;
    this.error  = undefined;
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
      this.router.navigate(['/browse']);
    } catch {
      this.error = 'Failed to save. Please try again.';
    } finally {
      this.saving = false;
    }
  }

  /** "Remind me later" — leave onboarding; it prompts again on next login. */
  remindLater() {
    sessionStorage.setItem('onboardingPrompted', 'true');
    this.router.navigate(['/browse']);
  }

  /** "Don't ask again" — never auto-prompt this candidate to onboard again. */
  dontAskAgain() {
    localStorage.setItem('onboardingDismissed', 'true');
    sessionStorage.setItem('onboardingPrompted', 'true');
    this.router.navigate(['/browse']);
  }
}
