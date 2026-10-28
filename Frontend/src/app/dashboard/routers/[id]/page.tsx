export const instant = false;

import RouterDetail from "@/components/router/RouterDetail";

/** A business's own router; the backend answers 404 for any router it doesn't own. */
export default function Page() {
  return <RouterDetail scope="tenant" />;
}
