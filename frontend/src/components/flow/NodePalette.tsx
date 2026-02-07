import type { NodeTypeDescriptor } from '@/types/node';
import type { DragEvent } from 'react';

interface NodePaletteProps {
  nodeTypes: NodeTypeDescriptor[];
}

export function NodePalette({ nodeTypes }: NodePaletteProps) {
  const onDragStart = (event: DragEvent, nodeType: NodeTypeDescriptor) => {
    event.dataTransfer.setData('application/atadflow-node-type', JSON.stringify(nodeType));
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <div className="node-palette">
      <h3>Nodes</h3>
      {nodeTypes.map((nt) => (
        <div
          key={nt.id}
          className="palette-item"
          draggable
          onDragStart={(e) => onDragStart(e, nt)}
        >
          {nt.label}
        </div>
      ))}
    </div>
  );
}
