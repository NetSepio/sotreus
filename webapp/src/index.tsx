import React from "react";
import ReactDOM from "react-dom";
import "./index.css";
import App from "./App";

// import "@rainbow-me/rainbowkit/styles.css";
// import {
//   getDefaultWallets,
//   RainbowKitProvider,
//   midnightTheme,
// } from "@rainbow-me/rainbowkit";
// import { configureChains, createClient, WagmiConfig } from "wagmi";
// import { mainnet, polygon, optimism, arbitrum, goerli } from "wagmi/chains";
// import { publicProvider } from "wagmi/providers/public";
import { AuthProvider } from "./context/AuthContext";
import { PetraWallet } from "petra-plugin-wallet-adapter";
import { AptosWalletAdapterProvider } from "@aptos-labs/wallet-adapter-react";

// const { chains, provider, webSocketProvider } = configureChains(
//   [
//     mainnet,
//     polygon,
//     optimism,
//     arbitrum,
//     ...(process.env.REACT_APP_ENABLE_TESTNETS === "true" ? [goerli] : []),
//   ],
//   [publicProvider()]
// );

// const { connectors } = getDefaultWallets({
//   appName: "Sotreus",
//   chains,
//   projectId: process.env.WALLET_CONNECT_PROJECT_ID,
// });

// const wagmiClient = createClient({
//   autoConnect: true,
//   connectors,
//   provider,
//   webSocketProvider,
// });

const wallets = [new PetraWallet()];

ReactDOM.render(
  <React.StrictMode>
    {/* <WagmiConfig client={wagmiClient}>
      <RainbowKitProvider
        modalSize="compact"
        chains={chains}
        theme={midnightTheme({
          accentColor: "",
          accentColorForeground: "white",
          borderRadius: "medium",
          fontStack: "system",
          overlayBlur: "small",
        })}
      > */}
    <AptosWalletAdapterProvider plugins={wallets} autoConnect={true}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </AptosWalletAdapterProvider>
    {/* </RainbowKitProvider>
    </WagmiConfig> */}
  </React.StrictMode>,
  document.getElementById("root")
);
