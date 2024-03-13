// App.tsx
import { BrowserRouter, Route, Routes } from "react-router-dom";
import Dashboard from "./pages/Dashboard";
import LandingPage from "./pages/LandingPage";
import Server from "./pages/Server";
import AuthComponent from "./components/Auth";

const App = () => {
  return (
    <BrowserRouter>
      <div>
        <Routes>
          <Route path="/" element={<LandingPage />} />
          <Route path="/auth" element={<AuthComponent />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/server" element={<Server />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
};

export default App;
