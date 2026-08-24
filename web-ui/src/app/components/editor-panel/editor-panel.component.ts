import {
  AfterViewInit, ChangeDetectorRef, Component, ElementRef, EventEmitter, OnDestroy, Output, ViewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { WsMessage } from '../../models';
import { WorkspaceService } from '../../services/workspace.service';
import { MonacoService } from '../../services/monaco.service';
import { languageFor } from '../../monaco';

interface Tab {
  path: string;
  name: string;
  language: string;
  model: any;
  dirty: boolean;
}

@Component({
  selector: 'app-editor-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './editor-panel.component.html',
  styleUrls: ['./editor-panel.component.css']
})
export class EditorPanelComponent implements AfterViewInit, OnDestroy {

  @Output() status = new EventEmitter<string>();

  @ViewChild('editorHost', { static: false }) editorHost!: ElementRef<HTMLElement>;

  tabs: Tab[] = [];
  activePath = '';

  private monaco: any;
  private editor: any;
  private saveTimers = new Map<string, any>();
  private registeredLangs = new Set<string>();
  private subscriptions: Subscription[] = [];
  private pendingContent = new Map<string, string>();

  constructor(
    private workspace: WorkspaceService,
    private monacoService: MonacoService,
    private cdr: ChangeDetectorRef
  ) {
    // Subscribe immediately: fileContent may arrive before Monaco has
    // finished loading; such messages are buffered in pendingContent.
    this.subscriptions.push(this.workspace.messages$.subscribe((m) => this.onWs(m)));
  }

  async ngAfterViewInit(): Promise<void> {
    try {
      this.monaco = await this.monacoService.load();
      this.editor = this.monaco.editor.create(this.editorHost.nativeElement, {
        theme: 'vs-dark',
        fontSize: 13,
        minimap: { enabled: false },
        automaticLayout: true,
        scrollBeyondLastLine: false
      });
      this.editor.addCommand(
        this.monaco.KeyMod.CtrlCmd | this.monaco.KeyCode.Space,
        () => this.editor.trigger('keyboard', 'editor.action.triggerSuggest', {})
      );
      this.editor.onDidChangeModelContent(() => this.onContentChanged());
      for (const [path, content] of this.pendingContent) {
        this.applyFileContent(path, content);
      }
      this.pendingContent.clear();
    } catch (e) {
      this.status.emit('Monaco failed to load: ' + e);
    }
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach((s) => s.unsubscribe());
    this.saveTimers.forEach((t) => clearTimeout(t));
    this.tabs.forEach((t) => t.model?.dispose());
    this.editor?.dispose();
  }

  /** Called from the tree: opens the file in a new tab (or activates it). */
  openTab(path: string): void {
    let tab = this.tabs.find((t) => t.path === path);
    if (!tab) {
      tab = {
        path,
        name: path.split('/').pop() ?? path,
        language: languageFor(path),
        model: null,
        dirty: false
      };
      this.tabs.push(tab);
      this.workspace.loadFile(path);
    }
    this.activate(tab);
  }

  activate(tab: Tab): void {
    this.activePath = tab.path;
    if (this.editor) {
      this.editor.setModel(tab.model);
    }
    this.cdr.detectChanges();
  }

  closeTab(tab: Tab, event: Event): void {
    event.stopPropagation();
    if (tab.dirty && tab.model) {
      this.workspace.save(tab.path, tab.model.getValue());
    }
    if (this.saveTimers.has(tab.path)) {
      clearTimeout(this.saveTimers.get(tab.path));
      this.saveTimers.delete(tab.path);
    }
    const index = this.tabs.indexOf(tab);
    this.tabs.splice(index, 1);
    tab.model?.dispose();
    if (this.activePath === tab.path) {
      const next = this.tabs[Math.min(index, this.tabs.length - 1)];
      if (next) {
        this.activate(next);
      } else {
        this.activePath = '';
        this.editor?.setModel(null);
      }
    }
    this.cdr.detectChanges();
  }

  /** Closes every tab (used when a new workspace is uploaded). */
  clear(): void {
    this.tabs.forEach((t) => t.model?.dispose());
    this.tabs = [];
    this.activePath = '';
    this.saveTimers.forEach((t) => clearTimeout(t));
    this.saveTimers.clear();
    this.editor?.setModel(null);
    this.cdr.detectChanges();
  }

  /** Flushes pending debounced saves and saves every dirty tab immediately. */
  saveAllDirty(): void {
    for (const tab of this.tabs) {
      if (this.saveTimers.has(tab.path)) {
        clearTimeout(this.saveTimers.get(tab.path));
        this.saveTimers.delete(tab.path);
      }
      if (tab.dirty && tab.model) {
        this.workspace.save(tab.path, tab.model.getValue());
        tab.dirty = false;
      }
    }
    this.cdr.detectChanges();
  }

  private onWs(message: WsMessage): void {
    if (message.type === 'fileContent' && message.path) {
      if (!this.monaco) {
        this.pendingContent.set(message.path, message.content ?? '');
        return;
      }
      this.applyFileContent(message.path, message.content ?? '');
    }
    if (message.type === 'saved' && message.path) {
      const tab = this.tabs.find((t) => t.path === message.path);
      if (tab) {
        tab.dirty = false;
        this.status.emit('saved ' + message.path);
        this.cdr.detectChanges();
      }
    }
    if (message.type === 'error' && message.message) {
      this.status.emit('error: ' + message.message);
    }
  }

  private applyFileContent(path: string, content: string): void {
    const tab = this.tabs.find((t) => t.path === path);
    if (!tab) {
      return;
    }
    tab.model?.dispose();
    const model = this.monaco.editor.createModel(
      content,
      tab.language,
      this.monaco.Uri.parse('file:///' + path)
    );
    tab.model = model;
    tab.dirty = false;
    this.ensureCompletion(tab.language);
    if (this.activePath === path && this.editor) {
      this.editor.setModel(model);
    }
    this.cdr.detectChanges();
  }

  private onContentChanged(): void {
    const tab = this.activeTab();
    if (!tab || !tab.model) {
      return;
    }
    tab.dirty = true;
    if (this.saveTimers.has(tab.path)) {
      clearTimeout(this.saveTimers.get(tab.path));
    }
    // Auto-save 2 seconds after the last input.
    this.saveTimers.set(tab.path, setTimeout(() => this.saveTab(tab), 2000));
    this.cdr.detectChanges();
  }

  private saveTab(tab: Tab): void {
    this.saveTimers.delete(tab.path);
    if (!tab.model) {
      return;
    }
    this.workspace.save(tab.path, tab.model.getValue());
  }

  private activeTab(): Tab | undefined {
    return this.tabs.find((t) => t.path === this.activePath);
  }

  /** Registers a completion provider (server-backed) once per language. */
  private ensureCompletion(language: string): void {
    if (this.registeredLangs.has(language) || !this.monaco) {
      return;
    }
    this.registeredLangs.add(language);
    const monaco = this.monaco;
    monaco.languages.registerCompletionItemProvider(language, {
      triggerCharacters: ['.', '(', '<'],
      provideCompletionItems: async (model: any, position: any) => {
        try {
          const result = await this.workspace.complete({
            path: model.uri.path,
            line: position.lineNumber,
            column: position.column,
            text: model.getValue()
          });
          if (!result.success || !result.suggestions.length) {
            return { suggestions: [] };
          }
          return {
            suggestions: result.suggestions.map((s: string) => ({
              label: s,
              kind: monaco.languages.CompletionItemKind.Snippet,
              insertText: s
            }))
          };
        } catch {
          return { suggestions: [] };
        }
      }
    });
  }
}
