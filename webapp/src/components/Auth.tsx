// AuthComponent.tsx
import React, { useEffect } from "react";
import { useSearchParams, redirect } from "react-router-dom";
import { verifyToken } from "../modules/api";

const AuthComponent = () => {
  const verify = async (token: string | null) => {
    const res = await verifyToken(token);
    console.log(res.data);
  };

  useEffect(() => {
    const [searchParams, setSearchParams] = useSearchParams();
    const token = searchParams.get("token");
    console.log("Token:", token);
    verify(token);
  }, []);

  return (
    <div>
      <p>Authenticating...</p>
    </div>
  );
};

export default AuthComponent;
