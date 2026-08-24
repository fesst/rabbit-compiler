import { Injectable } from '@angular/core';
import { loadMonaco } from '../monaco';

/** Injectable wrapper around the AMD monaco loader so tests can fake it. */
@Injectable({ providedIn: 'root' })
export class MonacoService {
  load(): Promise<any> {
    return loadMonaco();
  }
}
