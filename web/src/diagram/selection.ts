export type CanvasSelection =
  | { kind: 'classifier'; classifierId: string }
  | { kind: 'attribute'; classifierId: string; elementId: string }
  | { kind: 'operation'; classifierId: string; elementId: string }
  | { kind: 'constraint'; classifierId: string; elementId: string }
  | { kind: 'relationship'; relationshipId: string };
