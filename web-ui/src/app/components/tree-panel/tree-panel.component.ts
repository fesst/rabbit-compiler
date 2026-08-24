import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TreeNode } from '../../models';

@Component({
  selector: 'app-tree-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './tree-panel.component.html',
  styleUrls: ['./tree-panel.component.css']
})
export class TreePanelComponent {
  @Input() set tree(value: TreeNode | null) {
    this._tree = value;
    this.expanded.clear();
    if (value) {
      this.expanded.add(value.path); // root expanded by default
    }
    this.rebuild();
  }
  get tree(): TreeNode | null {
    return this._tree;
  }

  @Output() openFile = new EventEmitter<string>();

  _tree: TreeNode | null = null;
  rows: Array<{ node: TreeNode; depth: number }> = [];
  expanded = new Set<string>();

  toggle(node: TreeNode): void {
    if (node.type !== 'folder') {
      return;
    }
    if (this.expanded.has(node.path)) {
      this.expanded.delete(node.path);
    } else {
      this.expanded.add(node.path);
    }
    this.rebuild();
  }

  onDblClick(node: TreeNode): void {
    if (node.type === 'file') {
      this.openFile.emit(node.path);
    }
  }

  isExpanded(node: TreeNode): boolean {
    return this.expanded.has(node.path);
  }

  private rebuild(): void {
    this.rows = [];
    if (!this._tree) {
      return;
    }
    this.walk(this._tree, 0);
  }

  private walk(node: TreeNode, depth: number): void {
    this.rows.push({ node, depth });
    if (node.type === 'folder' && this.expanded.has(node.path)) {
      (node.children ?? []).forEach((child) => this.walk(child, depth + 1));
    }
  }
}
