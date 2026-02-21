import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SOTREUS | Securing the next node",
  description: "Zero Trust. Absolute Resilience. SOTREUS is a cybersecurity product built by NetSepio.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <head>
        <link href="https://api.fontshare.com/v2/css?f[]=general-sans@400,500,600&display=swap" rel="stylesheet" />
      </head>
      <body>
        {children}
      </body>
    </html>
  );
}
