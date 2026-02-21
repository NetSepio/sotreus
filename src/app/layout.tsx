import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Web3 Landing Page",
  description: "Web3 at the Speed of Experience",
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
