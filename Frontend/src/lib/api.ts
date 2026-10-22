import axios from "axios";

export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "";

let pendingRequests = 0;
const listeners = new Set<() => void>();
const trackedRequests = new WeakSet<object>();
export const getPendingRequests = () => pendingRequests;
export function subscribeRequests(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
function startRequest(config: object) {
  trackedRequests.add(config);
  pendingRequests += 1;
  listeners.forEach(listener => listener());
}
function finishRequest(config?: object) {
  if (!config || !trackedRequests.delete(config)) return;
  pendingRequests = Math.max(0, pendingRequests - 1);
  listeners.forEach(listener => listener());
}

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 20000,
  headers: {
    "Content-Type": "application/json",
  },
});

api.interceptors.request.use(
  (config) => {
    if (typeof window !== "undefined") {
      const token = localStorage.getItem("wb_token");
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
    }
    if (typeof window !== "undefined") startRequest(config);
    return config;
  },
  (error) => Promise.reject(error)
);

api.interceptors.response.use(
  (response) => { finishRequest(response.config); return response; },
  (error) => {
    finishRequest(error.config);
    if (typeof window !== "undefined" && error.response?.status === 401) {
      localStorage.removeItem("wb_token");
      localStorage.removeItem("wb_user");
      if (window.location.pathname !== "/login") {
        window.location.href = "/login";
      }
    }
    return Promise.reject(error);
  }
);

export default api;
