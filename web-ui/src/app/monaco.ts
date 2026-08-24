// Loads monaco-editor through its AMD build served from /assets/monaco/vs
// (copied from node_modules in angular.json). Workers are served from the same
// folder through workerMain.js, so no bundler-specific worker config is needed.
let monacoPromise: Promise<any> | null = null;

export function loadMonaco(): Promise<any> {
  if (monacoPromise) {
    return monacoPromise;
  }
  monacoPromise = new Promise<any>((resolve, reject) => {
    const w = window as any;
    const timeout = window.setTimeout(() => reject(new Error('Monaco failed to load')), 30000);
    w.MonacoEnvironment = {
      getWorkerUrl: () => '/assets/monaco/vs/base/worker/workerMain.js'
    };
    const requireAmd = w.require;
    if (!requireAmd) {
      reject(new Error('Monaco AMD loader missing (loader.js not loaded)'));
      return;
    }
    requireAmd.config({ paths: { vs: '/assets/monaco/vs' } });
    requireAmd(['vs/editor/editor.main'], (monaco: any) => {
      window.clearTimeout(timeout);
      resolve(monaco);
    });
  });
  return monacoPromise;
}

const LANG_BY_EXT: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript', mts: 'typescript', cts: 'typescript',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript', cjs: 'javascript',
  java: 'java', py: 'python', go: 'go', rs: 'rust', rb: 'ruby', php: 'php',
  c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp', hpp: 'cpp', cs: 'csharp',
  json: 'json', jsonc: 'json', yaml: 'yaml', yml: 'yaml', xml: 'xml',
  html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less',
  md: 'markdown', sh: 'shell', bash: 'shell', zsh: 'shell', sql: 'sql',
  kt: 'kotlin', kts: 'kotlin', gradle: 'groovy', dockerfile: 'dockerfile',
  ini: 'ini', properties: 'ini', toml: 'ini'
};

export function languageFor(path: string): string {
  const lower = path.toLowerCase();
  if (lower.endsWith('dockerfile')) {
    return 'dockerfile';
  }
  const ext = lower.split('.').pop() ?? '';
  return LANG_BY_EXT[ext] ?? 'plaintext';
}
