export type NodeType = 'folder' | 'file';

export interface TreeNode {
  type: NodeType;
  name: string;
  path: string;
  size?: number;
  children?: TreeNode[];
}

export interface CompletionRequest {
  path: string;
  line: number;
  column: number;
  text: string;
}

export interface CompletionResult {
  success: boolean;
  message?: string;
  suggestions: string[];
}

export interface CompileResult {
  success: boolean;
  resultType?: string;
  message?: string;
}

export interface WsMessage {
  type: string;
  workspaceId?: string;
  tree?: TreeNode;
  path?: string;
  content?: string;
  timestamp?: string;
  success?: boolean;
  resultType?: string;
  message?: string;
  suggestions?: string[];
  requestId?: string;
  line?: number;
  column?: number;
  text?: string;
}

export type NotificationKind = 'info' | 'success' | 'error' | 'warn';

export interface Notification {
  kind: NotificationKind;
  text: string;
  time: Date;
}
