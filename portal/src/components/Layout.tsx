import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/auth";

const links = [
  { to: "/sound", label: "Sound" },
  { to: "/training", label: "Training review" },
  { to: "/metrics", label: "Accuracy" },
  { to: "/models", label: "Model versions" },
  { to: "/devices", label: "Devices" },
];

export default function Layout() {
  const { logout } = useAuth();
  const navigate = useNavigate();

  return (
    <div className="layout">
      <nav className="sidebar">
        <div className="brand">
          So<span className="accent">Nex</span> <span className="muted" style={{ fontSize: 12 }}>dev portal</span>
        </div>
        {links.map((l) => (
          <NavLink
            key={l.to}
            to={l.to}
            className={({ isActive }) => `navlink${isActive ? " active" : ""}`}
          >
            {l.label}
          </NavLink>
        ))}
        <div className="spacer" />
        <button
          className="btn-ghost"
          onClick={() => {
            logout();
            navigate("/login");
          }}
        >
          Sign out
        </button>
      </nav>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
