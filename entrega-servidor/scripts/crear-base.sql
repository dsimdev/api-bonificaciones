-- Login, base y usuario para desarrollo local. Correr con un login que tenga permiso de sysadmin
-- (por defecto, autenticacion de Windows del usuario que instalo la instancia):
--   & 'C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\SQLCMD.EXE' -S localhost,1433 -E -i scripts\crear-base.sql
--
-- Mismo enfoque que api-impuestos: la instalacion de los dos servicios tiene que ser la misma
-- para quien opera el servidor.
--
-- La contrasenia es de desarrollo. En el servidor va otra, por variable de entorno, y ahi se
-- restaura CHECK_POLICY = ON (ver docs/proyecto/deploy.md).
--
-- SQL Server separa LOGIN (nivel servidor, quien se autentica) de USER (nivel base, que permisos
-- tiene ahi).
--
-- Collation case-sensitive y accent-sensitive A PROPOSITO, igual que motorfiscal: la instalacion
-- tipica de SQL Server es case-insensitive, y eso rompe en silencio la garantia de una columna
-- UNIQUE como distribuidora.codigo -- 'dyssa' y 'Dyssa' entrarian como el mismo valor. Cambiar la
-- collation despues es costoso, asi que se fija al crear la base.

CREATE LOGIN bonificaciones WITH PASSWORD = 'bonificaciones', CHECK_POLICY = OFF;
GO

CREATE DATABASE bonificaciones COLLATE Latin1_General_100_CS_AS;
GO

USE bonificaciones;
GO

CREATE USER bonificaciones FOR LOGIN bonificaciones;
ALTER ROLE db_owner ADD MEMBER bonificaciones;
GO
