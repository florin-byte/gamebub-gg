-- Simulation only: silent stand-in for the VM2413 FM chip.
library ieee;
use ieee.std_logic_1164.all;
entity opll is
    port(
        xin    : in  std_logic;
        xout   : out std_logic;
        xena   : in  std_logic;
        d      : in  std_logic_vector(7 downto 0);
        a      : in  std_logic;
        cs_n   : in  std_logic;
        we_n   : in  std_logic;
        ic_n   : in  std_logic;
        mixout : out std_logic_vector(13 downto 0)
    );
end opll;
architecture sim of opll is
begin
    xout <= xin;
    mixout <= (others => '0');
end sim;
