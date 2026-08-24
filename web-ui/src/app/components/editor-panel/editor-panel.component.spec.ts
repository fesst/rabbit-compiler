import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { EditorPanelComponent } from './editor-panel.component';
import { WsMessage } from '../../models';
import { WorkspaceService } from '../../services/workspace.service';
import { MonacoService } from '../../services/monaco.service';

describe('EditorPanelComponent', () => {
  let component: EditorPanelComponent;
  let fixture: ComponentFixture<EditorPanelComponent>;
  let messages: Subject<WsMessage>;
  let workspace: any;
  let monacoService: any;
  let fakeEditor: any;
  let fakeMonaco: any;

  function buildFakeMonaco(): void {
    fakeEditor = {
      setModel: jasmine.createSpy('setModel'),
      addCommand: jasmine.createSpy('addCommand'),
      trigger: jasmine.createSpy('trigger'),
      dispose: jasmine.createSpy('dispose'),
      changeHandler: null,
      onDidChangeModelContent: jasmine.createSpy('onDidChangeModelContent')
        .and.callFake((cb: () => void) => { fakeEditor.changeHandler = cb; })
    };
    fakeMonaco = {
      editor: {
        create: jasmine.createSpy('create').and.returnValue(fakeEditor),
        createModel: jasmine.createSpy('createModel').and.callFake(
          (content: string, lang: string, uri: any) => ({
            getValue: () => content,
            uri,
            language: lang,
            dispose: jasmine.createSpy('modelDispose')
          })
        )
      },
      Uri: {
        parse: jasmine.createSpy('uriParse').and.callFake((p: string) => ({ path: p.replace('file://', '') }))
      },
      languages: {
        registerCompletionItemProvider: jasmine.createSpy('registerCompletionItemProvider'),
        CompletionItemKind: { Snippet: 0 }
      },
      KeyMod: { CtrlCmd: 1 },
      KeyCode: { Space: 2 }
    };
  }

  beforeEach(() => {
    buildFakeMonaco();
    messages = new Subject<WsMessage>();
    workspace = {
      messages$: messages.asObservable(),
      loadFile: jasmine.createSpy('loadFile'),
      save: jasmine.createSpy('save'),
      complete: jasmine.createSpy('complete').and.returnValue(Promise.resolve({ success: false, suggestions: [] })),
      compile: jasmine.createSpy('compile'),
      send: jasmine.createSpy('send')
    };
    monacoService = { load: jasmine.createSpy('load').and.returnValue(Promise.resolve(fakeMonaco)) };

    TestBed.configureTestingModule({
      imports: [EditorPanelComponent],
      providers: [
        { provide: WorkspaceService, useValue: workspace },
        { provide: MonacoService, useValue: monacoService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(EditorPanelComponent);
    component = fixture.componentInstance;
  });

  function loadFileViaTab(): void {
    component.openTab('src/main/java/A.java');
    messages.next({ type: 'fileContent', path: 'src/main/java/A.java', content: 'class A {}' });
  }

  it('buffers fileContent that arrives before Monaco is ready (regression)', async () => {
    let resolveMonaco: (m: any) => void = () => {};
    monacoService.load.and.returnValue(new Promise((r) => { resolveMonaco = r; }));
    fixture.detectChanges(); // ngAfterViewInit now awaits the deferred monaco

    component.openTab('src/main/java/A.java');
    expect(workspace.loadFile).toHaveBeenCalledWith('src/main/java/A.java');

    messages.next({ type: 'fileContent', path: 'src/main/java/A.java', content: 'class A {}' });
    fixture.detectChanges();
    // Monaco not loaded yet: content must be buffered, not applied.
    expect(fakeMonaco.editor.createModel).not.toHaveBeenCalled();

    resolveMonaco(fakeMonaco);
    await fixture.whenStable();

    // Buffered content reaches the editor once Monaco is ready.
    expect(fakeMonaco.editor.createModel).toHaveBeenCalledWith('class A {}', 'java', jasmine.anything());
    expect((component as any).tabs.length).toBe(1);
    expect(fakeEditor.setModel).toHaveBeenCalled();
  });

  it('creates the model immediately when Monaco is already loaded', fakeAsync(() => {
    fixture.detectChanges();
    flushMicrotasks();
    loadFileViaTab();
    flushMicrotasks();

    expect(fakeMonaco.editor.createModel).toHaveBeenCalledWith('class A {}', 'java', jasmine.anything());
    expect(fakeEditor.setModel).toHaveBeenCalled();
  }));

  it('auto-saves 2 seconds after the last input, not before', fakeAsync(() => {
    fixture.detectChanges();
    flushMicrotasks();
    loadFileViaTab();
    flushMicrotasks();

    fakeEditor.changeHandler(); // simulate a keystroke
    expect(workspace.save).not.toHaveBeenCalled();

    tick(1999);
    expect(workspace.save).not.toHaveBeenCalled();

    tick(2);
    expect(workspace.save).toHaveBeenCalledWith('src/main/java/A.java', 'class A {}');
  }));

  it('a later keystroke restarts the 2s auto-save window', fakeAsync(() => {
    fixture.detectChanges();
    flushMicrotasks();
    loadFileViaTab();
    flushMicrotasks();

    fakeEditor.changeHandler();
    tick(1500);
    fakeEditor.changeHandler(); // more typing
    tick(1500);
    expect(workspace.save).not.toHaveBeenCalled();

    tick(501);
    expect(workspace.save).toHaveBeenCalledTimes(1);
  }));

  it('saveAllDirty flushes a pending debounced save immediately and cancels the timer', fakeAsync(() => {
    fixture.detectChanges();
    flushMicrotasks();
    loadFileViaTab();
    flushMicrotasks();

    fakeEditor.changeHandler();
    tick(500); // inside the 2s window
    component.saveAllDirty();
    expect(workspace.save).toHaveBeenCalledWith('src/main/java/A.java', 'class A {}');

    tick(2500);
    expect(workspace.save).toHaveBeenCalledTimes(1);
  }));

  it('closing a dirty tab saves it immediately and removes the tab', fakeAsync(() => {
    fixture.detectChanges();
    flushMicrotasks();
    loadFileViaTab();
    flushMicrotasks();

    fakeEditor.changeHandler();
    tick(500);
    const tab = (component as any).tabs[0];
    component.closeTab(tab, new MouseEvent('click'));

    expect(workspace.save).toHaveBeenCalledWith('src/main/java/A.java', 'class A {}');
    expect((component as any).tabs.length).toBe(0);
  }));
});
