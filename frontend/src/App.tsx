import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AppShell } from '@/components/layout/AppShell';
import { FlowListPage } from '@/pages/FlowListPage';
import { FlowEditorPage } from '@/pages/FlowEditorPage';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/flows" element={<FlowListPage />} />
          <Route path="/flows/:flowId" element={<FlowEditorPage />} />
          <Route path="*" element={<Navigate to="/flows" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
