// App.tsx
import {
  createBrowserRouter,
  BrowserRouter,
  Route,
  Routes,
  RouterProvider,
} from "react-router-dom";
import Dashboard from "./pages/Dashboard";
import LandingPage from "./pages/LandingPage";
import Server from "./pages/Server";
import AuthComponent from "./components/Auth";

const App = () => {
  const router = createBrowserRouter([
    {
      path: "/",
      element: <LandingPage />,
    },
    {
      path: "/auth",
      element: <AuthComponent />,
    },
    {
      path: "/dashboard",
      element: <Dashboard />,
    },
    {
      path: "/server",
      element: <Server />,
    },
  ]);
  return (
    <RouterProvider router={router} />
    // <BrowserRouter>
    //   <div>
    //     <Routes>
    //       <Route path="/" element={<LandingPage />} />
    //       <Route path="/auth" element={<AuthComponent />} />
    //       <Route path="/dashboard" element={<Dashboard />} />
    //       <Route path="/server" element={<Server />} />
    //     </Routes>
    //   </div>
    // </BrowserRouter>
  );
};

export default App;
