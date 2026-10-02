import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { AuthProvider } from './auth/AuthContext.jsx';
import { createDevProvider } from './auth/providers/dev-provider.js';
import { createSsoProvider } from './auth/providers/sso-provider.js';
import { AUTH_MODE } from './config.js';
import './styles.css';

const authProvider = AUTH_MODE === 'sso' ? createSsoProvider() : createDevProvider();

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <AuthProvider provider={authProvider}>
      <App />
    </AuthProvider>
  </StrictMode>
);
