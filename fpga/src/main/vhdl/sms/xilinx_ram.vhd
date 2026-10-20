-- Xilinx replacements for the Altera RAM wrappers of SMS_MiSTer (sprom, spram, dpram).
--
-- Same entity names, generics and ports as the MiSTer files, so the vendored RTL uses them unchanged.
-- Behaviour of the altsyncram configuration they replace:
--   * the address is registered: q follows one clock after the address ("UNREGISTERED" output), or
--     two clocks when outdata_reg_* = "CLOCK0"/"CLOCK1" (extra output register);
--   * read during write on the same port returns the new data (NEW_DATA_NO_NBE_READ).
-- init_file is not supported: these RAMs start at zero. The only user in the SMS core is the internal
-- boot ROM (mboot.mif), which is only read with the BIOS enabled.

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity sprom is
	generic (
		init_file     : string := "";
		widthad_a     : natural;
		width_a       : natural := 8;
		outdata_reg_a : string := "UNREGISTERED"
	);
	port (
		address : in  std_logic_vector(widthad_a-1 downto 0);
		clock   : in  std_logic;
		q       : out std_logic_vector(width_a-1 downto 0)
	);
end sprom;

architecture rtl of sprom is
	type mem_t is array(0 to 2**widthad_a-1) of std_logic_vector(width_a-1 downto 0);
	signal mem  : mem_t := (others => (others => '0'));
	signal data : std_logic_vector(width_a-1 downto 0);
begin
	process (clock)
	begin
		if rising_edge(clock) then
			data <= mem(to_integer(unsigned(address)));
		end if;
	end process;

	unregistered : if outdata_reg_a = "UNREGISTERED" generate
		q <= data;
	end generate;
	registered : if outdata_reg_a /= "UNREGISTERED" generate
		process (clock)
		begin
			if rising_edge(clock) then
				q <= data;
			end if;
		end process;
	end generate;
end rtl;

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity spram is
	generic (
		init_file     : string := "none";
		widthad_a     : natural;
		width_a       : natural := 8;
		outdata_reg_a : string := "UNREGISTERED"
	);
	port (
		address : in  std_logic_vector(widthad_a-1 downto 0);
		clock   : in  std_logic;
		data    : in  std_logic_vector(width_a-1 downto 0);
		wren    : in  std_logic;
		q       : out std_logic_vector(width_a-1 downto 0)
	);
end spram;

architecture rtl of spram is
	type mem_t is array(0 to 2**widthad_a-1) of std_logic_vector(width_a-1 downto 0);
	signal mem  : mem_t := (others => (others => '0'));
	signal rdata : std_logic_vector(width_a-1 downto 0);
begin
	process (clock)
	begin
		if rising_edge(clock) then
			if wren = '1' then
				mem(to_integer(unsigned(address))) <= data;
				rdata <= data;
			else
				rdata <= mem(to_integer(unsigned(address)));
			end if;
		end if;
	end process;

	unregistered : if outdata_reg_a = "UNREGISTERED" generate
		q <= rdata;
	end generate;
	registered : if outdata_reg_a /= "UNREGISTERED" generate
		process (clock)
		begin
			if rising_edge(clock) then
				q <= rdata;
			end if;
		end process;
	end generate;
end rtl;

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity dpram is
	generic (
		init_file       : string := " ";
		widthad_a       : natural;
		width_a         : natural := 8;
		mixed_port_rdwr : string := "DONT_CARE";
		outdata_reg_a   : string := "UNREGISTERED";
		outdata_reg_b   : string := "UNREGISTERED"
	);
	port (
		address_a : in  std_logic_vector(widthad_a-1 downto 0);
		address_b : in  std_logic_vector(widthad_a-1 downto 0);
		clock_a   : in  std_logic;
		clock_b   : in  std_logic;
		data_a    : in  std_logic_vector(width_a-1 downto 0);
		data_b    : in  std_logic_vector(width_a-1 downto 0);
		wren_a    : in  std_logic := '1';
		wren_b    : in  std_logic := '1';
		q_a       : out std_logic_vector(width_a-1 downto 0);
		q_b       : out std_logic_vector(width_a-1 downto 0)
	);
end dpram;

architecture rtl of dpram is
	type mem_t is array(0 to 2**widthad_a-1) of std_logic_vector(width_a-1 downto 0);
	-- Two write ports on one memory: a shared variable, as Vivado expects for true dual-port RAM.
	shared variable mem : mem_t := (others => (others => '0'));
	signal rdata_a : std_logic_vector(width_a-1 downto 0);
	signal rdata_b : std_logic_vector(width_a-1 downto 0);
begin
	port_a : process (clock_a)
	begin
		if rising_edge(clock_a) then
			if wren_a = '1' then
				mem(to_integer(unsigned(address_a))) := data_a;
			end if;
			rdata_a <= mem(to_integer(unsigned(address_a)));
		end if;
	end process;

	port_b : process (clock_b)
	begin
		if rising_edge(clock_b) then
			if wren_b = '1' then
				mem(to_integer(unsigned(address_b))) := data_b;
			end if;
			rdata_b <= mem(to_integer(unsigned(address_b)));
		end if;
	end process;

	a_unregistered : if outdata_reg_a = "UNREGISTERED" generate
		q_a <= rdata_a;
	end generate;
	a_registered : if outdata_reg_a /= "UNREGISTERED" generate
		process (clock_a)
		begin
			if rising_edge(clock_a) then
				q_a <= rdata_a;
			end if;
		end process;
	end generate;

	b_unregistered : if outdata_reg_b = "UNREGISTERED" generate
		q_b <= rdata_b;
	end generate;
	b_registered : if outdata_reg_b /= "UNREGISTERED" generate
		process (clock_b)
		begin
			if rising_edge(clock_b) then
				q_b <= rdata_b;
			end if;
		end process;
	end generate;
end rtl;
