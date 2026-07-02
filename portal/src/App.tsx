import { Navigate, Route, Routes } from "react-router-dom";
import { RequireAuth } from "./auth/auth";
import Layout from "./components/Layout";
import LoginPage from "./pages/LoginPage";
import SoundPage from "./pages/SoundPage";
import TrainingPage from "./pages/TrainingPage";
import MetricsPage from "./pages/MetricsPage";
import ModelsPage from "./pages/ModelsPage";
import DevicesPage from "./pages/DevicesPage";

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<Navigate to="/sound" replace />} />
        <Route path="/sound" element={<SoundPage />} />
        <Route path="/training" element={<TrainingPage />} />
        <Route path="/metrics" element={<MetricsPage />} />
        <Route path="/models" element={<ModelsPage />} />
        <Route path="/devices" element={<DevicesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
