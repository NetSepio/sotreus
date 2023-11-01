import { useNavigate } from "react-router-dom";
import { useContext, useEffect, useState } from "react";
import { AuthContext } from "../context/AuthContext";
import { useAccount, useSignMessage, useNetwork } from "wagmi";
import { getChallengeId, getToken } from "../modules/api";
import { ConnectButton } from "@rainbow-me/rainbowkit";
import { useWallet } from "@aptos-labs/wallet-adapter-react";

const Header = () => {
  const navigate = useNavigate();
  const authContext = useContext(AuthContext);
  const useraddress = useAccount();
  const [message, setMessage] = useState<string>("");
  const [challengeId, setChallengeId] = useState<string>("");
  const [signature, setSignature] = useState<string | undefined>();
  const { signMessageAsync } = useSignMessage();
  const { isConnected, address } = useAccount();
  const [open, setOpen] = useState(false);
  const [Chain, setChain] = useState("");

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

  const handleOpen = () => {
    setOpen(!open);
  };

  const navigateDashboard = async () => {
    await navigate("/dashboard");
  };

  const navigateServer = async () => {
    await navigate("/server");
  };

  useEffect(() => {
    const storedToken = localStorage.getItem("token");
    if (storedToken) {
      authContext?.setIsSignedIn(true);
    }
    let timeoutId: string | number | NodeJS.Timeout | null = null;

    const getSignMessage = async () => {
      if (address == undefined) {
        if (timeoutId !== null) {
          clearTimeout(timeoutId);
        }

        timeoutId = setTimeout(() => {
          signOut();
        }, 500);
      } else if (!authContext?.isSignedIn) {
        if (timeoutId !== null) {
          clearTimeout(timeoutId);
        }

        const response = await getChallengeId(address);
        setMessage(response.data.eula + response.data.challangeId);
        setChallengeId(response.data.challangeId);
        if (response.data.isAuthorized == true) {
          authContext?.setIsAuthorized(true);
        } else {
          authContext?.setIsAuthorized(false);
        }
      }
    };

    getSignMessage();

    return () => {
      if (timeoutId !== null) {
        clearTimeout(timeoutId);
      }
    };
  }, [authContext?.isSignedIn, address]);

  const signMessage = async () => {
    const signature = await signMessageAsync({ message });
    setSignature(signature);
    //make a post request to the sotreus server with the signature and challengeId

    const response = await getToken(signature, challengeId);
    if (response.data.token) {
      //store the token in the session storage
      sessionStorage.setItem("token", response.data.token);
      localStorage.setItem("token", response.data.token);
      authContext?.setIsSignedIn(true);
    }
  };
  const signOut = () => {
    sessionStorage.removeItem("token");
    localStorage.removeItem("token");
    setMessage("");
    setSignature("");
    setChallengeId("");
    authContext?.setIsSignedIn(false);
  };
  const connectPetra = async () => {
    // wallets[0]
    //   .connect()
    //   .then((res) => {
    //     console.log(wallets[0]);
    //   })
    //   .catch((err) => {
    //     console.log(err);
    //   });
    connect(wallets[0].name);
  };
  const disconnectPetra = () => {
    disconnect();
  };

  const petraSign = async () => {
    const payload = {
      message: "Hello from Aptos Wallet Adapter",
      nonce: "random_string",
    };
    try {
      const response = await petraSignMesssage(payload);
      console.log("response", response);
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
                <button
                  onClick={handleOpen}
                  className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80 py-4"
                >
                  Select Chain
                </button>
                {open ? (
                  <div className="absolute left-0 z-10 mt-2 origin-top-right rounded-md bg-none shadow-lg ring-1 ring-black ring-opacity-5 focus:outline-none">
                    <div className="-m-4" role="none">
                      <button
                        className="text-black bg-blue-300 hover:bg-transparent hover:text-blue-200 mt-1 py-4 px-4 w-full rounded-t-md"
                        onClick={() => {
                          setChain("ethereum");
                          handleOpen();
                        }}
                      >
                        Ethereum
                      </button>
                      <button
                        className="text-black bg-blue-300 hover:bg-transparent hover:text-blue-200 mb-1 py-4 px-4 w-full rounded-b-md"
                        onClick={() => {
                          setChain("aptos");
                          handleOpen();
                        }}
                      >
                        Aptos
                      </button>
                    </div>
                  </div>
                ) : null}
              </li>
              {Chain == "aptos" && (!address || authContext?.isSignedIn) && (
                <li>
                  <button
                    onClick={() => connectPetra()}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    <img src="{wallets[0].icon}"></img> Connect{" "}
                    {wallets[0].name}
                  </button>
                </li>
              )}
              {Chain == "aptos" && (!address || authContext?.isSignedIn) && (
                <li>
                  <button
                    onClick={() => disconnectPetra()}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    disconnect
                  </button>
                </li>
              )}
              {Chain == "aptos" &&
                !(isConnected && authContext?.isSignedIn) && (
                  <li>
                    <button
                      onClick={petraSign}
                      className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                    >
                      Sign In
                    </button>
                  </li>
                )}

              {Chain == "ethereum" && (!address || authContext?.isSignedIn) && (
                <div className="border text-blue-200 border-blue rounded-md hover:bg-black hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80">
                  <div className="px-4 py-2">
                    <ConnectButton />
                  </div>
                </div>
              )}
              {!(isConnected && authContext?.isSignedIn) && address && (
                <li>
                  <button
                    onClick={signMessage}
                    className="border text-blue-200 border-blue hover:bg-blue-300 hover:border-black hover:text-black font-bold transition focus:ring focus:ring-blue-500 focus:ring-opacity-80"
                  >
                    Sign In
                  </button>
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
