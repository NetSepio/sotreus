import { Inter, JetBrains_Mono } from "next/font/google";
import type { Metadata } from "next";
import "./globals.css";

const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";
const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: "swap",
});
const jetBrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "SOTREUS | Funded Crypto Prop Trading",
    template: "%s | SOTREUS",
  },
  description:
    "Launch your crypto prop trading journey with SOTREUS. Access funded accounts up to $20,000 with clear rules, low entry tiers, and wallet-first onboarding.",
  applicationName: "SOTREUS",
  authors: [{ name: "SOTREUS" }],
  creator: "SOTREUS",
  publisher: "SOTREUS",
  keywords: [
    "crypto prop trading",
    "crypto prop firm",
    "funded crypto account",
    "solana prop trading",
    "funded trader account",
    "crypto trading capital",
  ],
  category: "finance",
  referrer: "origin-when-cross-origin",
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-snippet": -1,
      "max-image-preview": "large",
      "max-video-preview": -1,
    },
  },
  openGraph: {
    title: "SOTREUS | Funded Crypto Prop Trading",
    description:
      "Access funded crypto accounts up to $20,000 with low entry tiers. Built for traders who want clearer rules and a superior prop trading experience.",
    siteName: "SOTREUS",
    type: "website",
    locale: "en_US",
  },
  twitter: {
    card: "summary_large_image",
    title: "SOTREUS | Funded Crypto Prop Trading",
    description:
      "Launch your crypto prop trading journey with clear rules, low entry tiers, and funded accounts up to $20,000.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className={`${inter.variable} ${jetBrainsMono.variable}`}>
        {children}
      </body>
    </html>
  );
}
