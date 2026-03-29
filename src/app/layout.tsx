import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SOTREUS | PropAMM — Trade $2,000. Risk $100. Keep 90%.",
  description:
    "The first Prop-AMM for the fast-mover. Get $2,000 in funded trading capital. No KYC. No evaluations. Just execution. Keep 90% of your profits.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link
          rel="preconnect"
          href="https://fonts.gstatic.com"
          crossOrigin="anonymous"
        />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&family=JetBrains+Mono:wght@400;500;600;700&display=swap"
          rel="stylesheet"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
