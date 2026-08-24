import { Component, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { Notification, NotificationKind, TreeNode, WsMessage } from './models';
import { WorkspaceService } from './services/workspace.service';
import { TopBarComponent } from './components/top-bar/top-bar.component';
import { TreePanelComponent } from './components/tree-panel/tree-panel.component';
import { EditorPanelComponent } from './components/editor-panel/editor-panel.component';
import { FooterBarComponent } from './components/footer-bar/footer-bar.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, TopBarComponent, TreePanelComponent, EditorPanelComponent, FooterBarComponent],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnDestroy {

  @ViewChild(EditorPanelComponent) editorPanel!: EditorPanelComponent;

  workspaceId = '';
  tree: TreeNode | null = null;
  connected = false;
  notifications: Notification[] = [];
  status = '';
  compiling = false;

  private subscriptions: Subscription[] = [];

  constructor(private workspace: WorkspaceService) {
    this.subscriptions.push(this.workspace.messages$.subscribe((m) => this.onWs(m)));
    this.subscriptions.push(this.workspace.connection$.subscribe((c) => (this.connected = c)));
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach((s) => s.unsubscribe());
  }

  async onUpload(file: File): Promise<void> {
    this.notify('info', `Uploading ${file.name}…`);
    try {
      const id = await this.workspace.uploadZip(file);
      this.workspaceId = id;
      this.tree = null;
      this.editorPanel.clear();
      this.notify('info', `Workspace ${id.slice(0, 8)} uploaded, connecting over websocket…`);
      this.workspace.connect(id);
    } catch (e: any) {
      this.notify('error', `Upload failed: ${e?.message ?? e}`);
    }
  }

  onOpenFile(path: string): void {
    this.editorPanel.openTab(path);
  }

  async onCompile(): Promise<void> {
    this.editorPanel.saveAllDirty();
    this.compiling = true;
    this.notify('info', 'Compilation requested…');
    try {
      const result = await this.workspace.compile();
      if (result.success) {
        this.notify('success', 'Compilation OK' + (result.message ? ': ' + result.message : ''));
      } else {
        this.notify('error', 'Compilation failed: ' + (result.message || result.resultType || 'unknown error'));
      }
    } finally {
      this.compiling = false;
    }
  }

  private onWs(message: WsMessage): void {
    if (message.type === 'tree' && message.tree) {
      this.tree = message.tree;
      this.notify('success', `Workspace ${message.workspaceId?.slice(0, 8) ?? ''} loaded (${countFiles(message.tree)} files)`);
    }
    if (message.type === 'error' && message.message) {
      this.notify('error', message.message);
    }
  }

  private notify(kind: NotificationKind, text: string): void {
    this.notifications = [...this.notifications, { kind, text, time: new Date() }];
    // keep the feed bounded
    if (this.notifications.length > 200) {
      this.notifications = this.notifications.slice(-200);
    }
  }
}

function countFiles(node: TreeNode): number {
  if (node.type === 'file') {
    return 1;
  }
  return (node.children ?? []).reduce((sum, child) => sum + countFiles(child), 0);
}
