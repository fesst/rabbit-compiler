import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, firstValueFrom, Observable, Subject } from 'rxjs';
import { CompletionRequest, CompletionResult, CompileResult, WsMessage } from '../models';

/**
 * Talks to the source-changer service: the zip is uploaded over REST, then the
 * workspace (tree, files, saves, compilation, completion) lives over the
 * WebSocket endpoint /ws/workspace.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceService {

  private ws?: WebSocket;
  private workspaceId = '';
  private reconnectAttempts = 0;
  private nextRequestId = 0;
  private readonly messagesSubject = new Subject<WsMessage>();
  private readonly connectionSubject = new BehaviorSubject<boolean>(false);
  private readonly pendingCompile = new Map<string, (r: CompileResult) => void>();
  private readonly pendingComplete = new Map<string, (r: CompletionResult) => void>();

  messages$: Observable<WsMessage> = this.messagesSubject.asObservable();
  connection$: Observable<boolean> = this.connectionSubject.asObservable();

  constructor(private http: HttpClient) {
    this.messages$.subscribe((m) => {
      if (m.type === 'compileResult' && m.requestId) {
        this.pendingCompile.get(m.requestId)?.({
          success: !!m.success,
          resultType: m.resultType,
          message: m.message
        });
        this.pendingCompile.delete(m.requestId);
      }
      if (m.type === 'completeResult' && m.requestId) {
        this.pendingComplete.get(m.requestId)?.({
          success: !!m.success,
          message: m.message,
          suggestions: m.suggestions ?? []
        });
        this.pendingComplete.delete(m.requestId);
      }
    });
  }

  uploadZip(file: File): Promise<string> {
    const form = new FormData();
    form.append('file', file, file.name);
    return firstValueFrom(this.http.post<{ workspaceId: string }>('/api/workspaces', form)).then((r) => r.workspaceId);
  }

  connect(workspaceId: string): void {
    this.workspaceId = workspaceId;
    this.reconnectAttempts = 0;
    this.open();
  }

  private open(): void {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const ws = new WebSocket(`${proto}${location.host}/ws/workspace`);
    this.ws = ws;
    ws.onopen = () => {
      this.reconnectAttempts = 0;
      this.connectionSubject.next(true);
      this.send({ type: 'subscribe', workspaceId: this.workspaceId });
    };
    ws.onmessage = (ev) => {
      try {
        this.messagesSubject.next(JSON.parse(ev.data));
      } catch {
        // not JSON, ignore
      }
    };
    ws.onclose = () => {
      this.connectionSubject.next(false);
      this.ws = undefined;
      this.scheduleReconnect();
    };
    ws.onerror = () => ws.close();
  }

  private scheduleReconnect(): void {
    if (!this.workspaceId || this.reconnectAttempts >= 10) {
      return;
    }
    this.reconnectAttempts += 1;
    setTimeout(() => this.open(), 2000);
  }

  send(msg: WsMessage): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  loadFile(path: string): void {
    this.send({ type: 'file', path });
  }

  save(path: string, content: string): void {
    this.send({ type: 'save', path, content });
  }

  compile(): Promise<CompileResult> {
    const requestId = String(++this.nextRequestId);
    return new Promise<CompileResult>((resolve) => {
      this.pendingCompile.set(requestId, resolve);
      this.send({ type: 'compile', requestId });
      window.setTimeout(() => {
        if (this.pendingCompile.delete(requestId)) {
          resolve({ success: false, resultType: 'TIMEOUT', message: 'Compilation timed out' });
        }
      }, 60000);
    });
  }

  complete(req: CompletionRequest): Promise<CompletionResult> {
    const requestId = String(++this.nextRequestId);
    return new Promise<CompletionResult>((resolve) => {
      this.pendingComplete.set(requestId, resolve);
      this.send({ type: 'complete', requestId, ...req });
      window.setTimeout(() => {
        if (this.pendingComplete.delete(requestId)) {
          resolve({ success: false, message: 'Completion timed out', suggestions: [] });
        }
      }, 15000);
    });
  }
}
