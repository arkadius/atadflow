import { useState, useEffect } from 'react';
import { nodeTypesApi } from '@/api/nodeTypes';
import type { NodeTypeDescriptor } from '@/types/node';

export function useNodeTypes() {
  const [nodeTypes, setNodeTypes] = useState<NodeTypeDescriptor[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    nodeTypesApi.list().then((data) => {
      setNodeTypes(data);
      setLoading(false);
    });
  }, []);

  const getNodeType = (id: string) => nodeTypes.find((nt) => nt.id === id);

  return { nodeTypes, loading, getNodeType };
}
