import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <div className="py-16 text-center">
      <h1 className="text-xl font-semibold">Not found</h1>
      <p className="mt-2 text-slate-600">That page or ticket doesn't exist.</p>
      <Link to="/" className="mt-4 inline-block text-indigo-700 hover:underline">Back to tickets</Link>
    </div>
  );
}
