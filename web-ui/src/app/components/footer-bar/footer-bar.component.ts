import { AfterViewInit, Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Notification } from '../../models';

@Component({
  selector: 'app-footer-bar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './footer-bar.component.html',
  styleUrls: ['./footer-bar.component.css']
})
export class FooterBarComponent implements OnChanges, AfterViewInit {

  @Input() notifications: Notification[] = [];
  @Input() status = '';
  @Input() compiling = false;
  @Output() compile = new EventEmitter<void>();

  @ViewChild('scroll') scroll!: ElementRef<HTMLElement>;

  ngAfterViewInit(): void {
    this.scrollToBottom();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['notifications']) {
      this.scrollToBottom();
    }
  }

  private scrollToBottom(): void {
    requestAnimationFrame(() => {
      const el = this.scroll?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }
}
