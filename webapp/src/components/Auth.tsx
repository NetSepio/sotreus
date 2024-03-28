// AuthComponent.tsx
import React, { useEffect } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import { verifyToken } from "../modules/api";
import { useWallet } from "@aptos-labs/wallet-adapter-react";
import Cookies from "js-cookie";

const AuthComponent = () => {
  const navigate = useNavigate();
  const {
    connect,
    wallets,
    disconnect,
    wallet,
    account,
    network,
    connected,
    signMessage: petraSignMesssage,
    signMessageAndVerify,
  } = useWallet();

  const [walletAddress, setWalletAddress] = useSearchParams();

  const verify = async (token: string | null) => {
    console.log(wallets[0]);
    await verifyToken(token).then((res) => {
      Cookies.set("wallet_address", res.payload.walletAddress);
    });
    setWalletAddress(walletAddress);
    connect(wallets[0].name);
    Cookies.set("token", token!);
    navigate("/");
  };
  const [searchParams, setSearchParams] = useSearchParams();

  useEffect(() => {
    const token = searchParams.get("token");
    verify(token);
  }, []);

  return (
    <div>
      <p>Authenticating...</p>
    </div>
  );
};

export default AuthComponent;
