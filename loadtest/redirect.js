// k6 load test for the redirect endpoint.
// Usage:
//   1. docker compose up -d --build
//   2. Create a link and note its code (see README)
//   3. k6 run -e CODE=<shortCode> loadtest/redirect.js
import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const CODE = __ENV.CODE;

export const options = {
  scenarios: {
    redirects: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 1000), // requests per second
      timeUnit: "1s",
      duration: __ENV.DURATION || "60s",
      preAllocatedVUs: 200,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<50"],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/${CODE}`, { redirects: 0 });
  check(res, { "is 302": (r) => r.status === 302 });
}
