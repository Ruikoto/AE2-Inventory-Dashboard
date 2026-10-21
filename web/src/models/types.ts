export interface Icon {
  url: string;
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface Resource {
  id: string;
  groupId: string;
  type: string;
  registryId: string;
  name: string;
  quantity: string;
  unit: string;
  amountPerUnit: number;
  delta: string | null;
  rate: string | null;
  variantCount: number;
  icon?: Icon;
}
export interface Network {
  id: string;
  label: string;
  status: string;
  lastSeen: number;
  anchors: string[];
}
export interface Status {
  authenticated: boolean;
  authMode: string;
  worldId?: string;
  historyStatus?: string;
  historyBytes?: number;
  droppedSamples?: number;
  assets?: { status: string; entries: number };
}
export interface Inventory {
  worldId: string;
  networkId: string;
  available: boolean;
  status: string;
  timestamp?: number;
  version?: number;
  stale?: boolean;
  windowMs?: number;
  rows: Resource[];
  offset?: number;
  total: number;
}
export interface Point {
  timestamp: number;
  segment: string;
  quantity: string;
  min: string;
  max: string;
  firstAt: number;
  lastAt: number;
  samples: number;
  rate?: string | null;
}
export interface Series {
  id: string;
  points: Point[];
  delta?: string;
  rate?: string;
  complete: boolean;
}
export interface History {
  from: number;
  to: number;
  step: number;
  availableFrom: number;
  truncated: boolean;
  series: Series[];
}
export interface NetworkEvent {
  timestamp: number;
  type: string;
  related: string;
}
