import {
  Component, EventEmitter, HostListener, Input, Output, ElementRef,
  forwardRef, ChangeDetectionStrategy, ChangeDetectorRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

export interface OrgOption {
  value: string;
  label: string;
  defaultValidity: number | null;
}
export interface OrgGroup {
  label: string;
  orgs: string[];
}

/** Searchable / typeable combobox for the certification "Issuing organization"
 *  field. Native <select> doesn't let CSS control the open-state height, so
 *  with 19 options the list gets unwieldy. This component:
 *   - shows a clear text input with the current label (or placeholder)
 *   - opens a compact panel limited to ~5-6 rows with scroll
 *   - filters options live as the recruiter types
 *   - preserves group headers so categories stay scannable
 *   - works as a regular form control (ControlValueAccessor) so it plugs
 *     directly into the existing reactive form via formControlName
 */
@Component({
  selector: 'app-org-combobox',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './org-combobox.component.html',
  styleUrl: './org-combobox.component.css',
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => OrgComboboxComponent),
    multi: true,
  }],
})
export class OrgComboboxComponent implements ControlValueAccessor {
  @Input() groups: OrgGroup[] = [];
  @Input() orgs:   OrgOption[] = [];
  @Input() placeholder: string = 'Any organization';
  @Input() anyLabel:    string = 'Any organization';
  @Output() valueChange = new EventEmitter<string | null>();

  open = false;
  query = '';
  selectedValue: string | null = null;
  disabled = false;

  private onChange: (v: string | null) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(private host: ElementRef, private cdr: ChangeDetectorRef) {}

  // ── ControlValueAccessor ──────────────────────────────────────────────
  writeValue(v: string | null): void { this.selectedValue = v ?? null; this.cdr.markForCheck(); }
  registerOnChange(fn: any): void { this.onChange = fn; }
  registerOnTouched(fn: any): void { this.onTouched = fn; }
  setDisabledState(disabled: boolean): void { this.disabled = disabled; this.cdr.markForCheck(); }

  // ── UI helpers ────────────────────────────────────────────────────────
  selectedLabel(): string {
    if (!this.selectedValue) return '';
    return this.orgs.find(o => o.value === this.selectedValue)?.label ?? this.selectedValue;
  }

  orgLabel(code: string): string {
    return this.orgs.find(o => o.value === code)?.label ?? code;
  }

  openPanel(): void {
    if (this.disabled) return;
    this.open = true;
    this.query = '';
    this.cdr.markForCheck();
  }

  closePanel(): void {
    if (!this.open) return;
    this.open = false;
    this.onTouched();
    this.cdr.markForCheck();
  }

  onQueryInput(ev: Event): void {
    this.query = (ev.target as HTMLInputElement).value;
    if (!this.open) this.open = true;
    this.cdr.markForCheck();
  }

  select(code: string | null): void {
    this.selectedValue = code;
    this.onChange(code);
    this.valueChange.emit(code);
    this.closePanel();
  }

  clear(ev?: Event): void {
    ev?.stopPropagation();
    this.select(null);
  }

  filteredGroups(): OrgGroup[] {
    const q = this.query.trim().toLowerCase();
    if (!q) return this.groups;
    return this.groups
      .map(g => ({
        label: g.label,
        orgs: g.orgs.filter(code => this.orgLabel(code).toLowerCase().includes(q)),
      }))
      .filter(g => g.orgs.length > 0);
  }

  // ── Outside-click closes the panel ────────────────────────────────────
  @HostListener('document:click', ['$event'])
  onDocClick(ev: MouseEvent): void {
    if (!this.open) return;
    if (!this.host.nativeElement.contains(ev.target)) this.closePanel();
  }

  // ── Keyboard: Escape closes, Enter picks the only remaining match ────
  @HostListener('document:keydown.escape')
  onEscape(): void { this.closePanel(); }
}
