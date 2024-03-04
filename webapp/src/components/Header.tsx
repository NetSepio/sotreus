import { useNavigate } from "react-router-dom";
import { useContext, useEffect, useState } from "react";
import { AuthContext } from "../context/AuthContext";
import { getChallengeId, getToken } from "../modules/api";
import { useWallet } from "@aptos-labs/wallet-adapter-react";
import Cookies from "js-cookie";

const Header = () => {
  const navigate = useNavigate();
  const authContext = useContext(AuthContext);

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

  const navigateDashboard = async () => {
    await navigate("/dashboard");
  };

  useEffect(() => {
    const storedToken = Cookies.get("token");
    if (storedToken && connected) {
      authContext?.setIsSignedIn(true);
    }

    let timeoutId: string | number | NodeJS.Timeout | null = null;

    return () => {
      if (timeoutId !== null) {
        clearTimeout(timeoutId);
      }
    };
  }, [authContext?.isSignedIn, account?.address, connected]);

  const connectPetra = async () => {
    connect(wallets[0].name);
  };

  const disconnectPetra = () => {
    disconnect();
  };

  const petraSign = async () => {
    try {
      const Challengeresponse = await getChallengeId(account?.address);

      const payload = {
        message: Challengeresponse.data.eula,
        nonce: Challengeresponse.data.challangeId,
      };

      const signres = await petraSignMesssage(payload);
      console.log("response", signres);

      const response = await getToken(
        `0x${signres?.signature}`,
        Challengeresponse.data.challangeId,
        account?.publicKey
      );
      if (response.data.token) {
        Cookies.set("token", response.data.token);
        authContext?.setIsSignedIn(true);
        authContext?.setIsAuthorized(true);
      }
    } catch (error: any) {
      console.log("error", error);
    }
  };

  return (
    <div>
      <div className="navbar">
        <div className="flex-1">
          <a
            href="/"
            className="btn btn-ghost normal-case text-transparent bg-clip-text bg-gradient-to-r from-blue-400 to-blue-200 text-2xl md:text-3xl"
          >
            Sotreus
          </a>
        </div>
        <div className="flex-none">
          <ul className="menu menu-horizontal px-1">
            <div className="flex space-x-2">
              <li className="relative inline-block text-left">
                <div className="absolute left-0 z-10 mt-2 origin-top-right rounded-md bg-none shadow-lg ring-1 ring-black ring-opacity-5 focus:outline-none">
                  <div className="-m-4" role="none"></div>
                </div>
              </li>
              {!connected && (
                <li>
                  <button
                    onClick={() => connectPetra()}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    <img src={`${wallets[0].icon}`} width={20}></img> Connect{" "}
                    {wallets[0].name}
                  </button>
                </li>
              )}
              {connected && !authContext?.isSignedIn && (
                <li>
                  <button
                    onClick={petraSign}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    Sign In
                  </button>
                </li>
              )}
              {connected && (
                <li>
                  <div className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80">
                    Account: {account?.address.slice(0, 8)}...
                  </div>
                </li>
              )}
              <li>
                <button
                  onClick={navigateDashboard}
                  className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                >
                  Dashboard
                </button>
              </li>
            </div>
          </ul>
        </div>
      </div>
    </div>
  );
};

export default Header;
