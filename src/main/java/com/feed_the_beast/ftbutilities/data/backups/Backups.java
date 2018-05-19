package com.feed_the_beast.ftbutilities.data.backups;

import com.feed_the_beast.ftblib.lib.data.Universe;
import com.feed_the_beast.ftblib.lib.io.DataReader;
import com.feed_the_beast.ftblib.lib.util.CommonUtils;
import com.feed_the_beast.ftblib.lib.util.FileUtils;
import com.feed_the_beast.ftblib.lib.util.JsonUtils;
import com.feed_the_beast.ftblib.lib.util.StringUtils;
import com.feed_the_beast.ftblib.lib.util.text_components.Notification;
import com.feed_the_beast.ftbutilities.FTBUtilities;
import com.feed_the_beast.ftbutilities.FTBUtilitiesConfig;
import com.feed_the_beast.ftbutilities.events.BackupEvent;
import com.feed_the_beast.ftbutilities.net.MessageBackupProgress;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ThreadedFileIOBase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public enum Backups
{
	INSTANCE;

	public static final ResourceLocation NOTIFICATION_ID = new ResourceLocation(FTBUtilities.MOD_ID, "backup");

	public final List<Backup> backups = new ArrayList<>();
	public File backupsFolder;
	public long nextBackup = -1L;
	public int doingBackup = 0;

	private int currentFile = 0;
	private int totalFiles = 0;
	private String currentFileName = "";

	public void init()
	{
		backupsFolder = FTBUtilitiesConfig.backups.folder.isEmpty() ? new File(CommonUtils.folderMinecraft, "/backups/") : new File(FTBUtilitiesConfig.backups.folder);
		doingBackup = 0;
		backups.clear();

		JsonElement element = DataReader.get(new File(backupsFolder, "backups.json")).safeJson();

		if (element.isJsonArray())
		{
			try
			{
				for (JsonElement e : element.getAsJsonArray())
				{
					JsonObject json = e.getAsJsonObject();

					if (!json.has("size"))
					{
						json.addProperty("size", FileUtils.getSize(new File(backupsFolder, json.get("file").getAsString())));
					}

					backups.add(new Backup(json));
				}
			}
			catch (Throwable ex)
			{
				ex.printStackTrace();
			}
		}

		FTBUtilities.LOGGER.info("Backups folder - " + backupsFolder.getAbsolutePath());
	}

	public void tick(Universe universe, long now)
	{
		if (nextBackup > 0L && nextBackup <= now)
		{
			run(universe.server, universe.server, "");
		}

		if (doingBackup > 1)
		{
			doingBackup = 0;

			try
			{
				for (WorldServer w : universe.server.worlds)
				{
					if (w != null && w.disableLevelSaving)
					{
						w.disableLevelSaving = false;
					}
				}
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
			}

			if (!FTBUtilitiesConfig.backups.silent)
			{
				new MessageBackupProgress(0, 0).sendToAll();
			}

		}
		else if (doingBackup > 0)
		{
			if (currentFile == 0 || now % CommonUtils.TICKS_SECOND * 2L == 0 || currentFile == totalFiles - 1)
			{
				FTBUtilities.LOGGER.info("[" + currentFile + " | " + StringUtils.formatDouble((currentFile / (double) totalFiles) * 100D) + "%]: " + currentFileName);
			}

			if (!FTBUtilitiesConfig.backups.silent)
			{
				new MessageBackupProgress(currentFile, totalFiles).sendToAll();
			}
		}
	}

	public void notifyAll(MinecraftServer server, Function<ICommandSender, ITextComponent> function, boolean error)
	{
		for (EntityPlayerMP player : server.getPlayerList().getPlayers())
		{
			ITextComponent component = function.apply(player);
			component.getStyle().setColor(error ? TextFormatting.DARK_RED : TextFormatting.LIGHT_PURPLE);
			Notification.of(NOTIFICATION_ID, component).setImportant(true).send(server, null);
		}

		FTBUtilities.LOGGER.info(function.apply(null).getUnformattedText());
	}

	public boolean run(MinecraftServer server, ICommandSender sender, String customName)
	{
		if (doingBackup != 0)
		{
			return false;
		}

		boolean auto = !(sender instanceof EntityPlayerMP);

		if (auto && !FTBUtilitiesConfig.backups.enabled)
		{
			return false;
		}

		notifyAll(server, player -> FTBUtilities.lang(player, "ftbutilities.lang.backup.start", sender.getName()), false);
		nextBackup = server.getWorld(0).getTotalWorldTime() + FTBUtilitiesConfig.backups.ticks();

		try
		{
			if (server.getPlayerList() != null)
			{
				server.getPlayerList().saveAllPlayerData();
			}

			try
			{
				for (int i = 0; i < server.worlds.length; ++i)
				{
					if (server.worlds[i] != null)
					{
						WorldServer worldserver = server.worlds[i];
						worldserver.disableLevelSaving = true;
						worldserver.saveAllChunks(true, null);
					}
				}
			}
			catch (Exception ex1)
			{
				notifyAll(server, player -> FTBUtilities.lang(player, "ftbutilities.lang.backup.saving_failed"), true);
			}
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}

		File wd = server.getWorld(0).getSaveHandler().getWorldDirectory();
		doingBackup = 1;

		ThreadedFileIOBase.getThreadedIOInstance().queueIO(() ->
		{
			doBackup(server, wd, customName);
			return false;
		});

		return true;
	}

	private void doBackup(MinecraftServer server, File src, String customName)
	{
		Calendar time = Calendar.getInstance();
		File dstFile = null;
		boolean success = false;
		StringBuilder out = new StringBuilder();

		if (customName.isEmpty())
		{
			appendNum(out, time.get(Calendar.YEAR), '-');
			appendNum(out, time.get(Calendar.MONTH) + 1, '-');
			appendNum(out, time.get(Calendar.DAY_OF_MONTH), '-');
			appendNum(out, time.get(Calendar.HOUR_OF_DAY), '-');
			appendNum(out, time.get(Calendar.MINUTE), '-');
			appendNum(out, time.get(Calendar.SECOND), '\0');
		}
		else
		{
			out.append(customName);
		}

		Exception error = null;
		long totalSize = 0L;

		try
		{
			LinkedHashMap<File, String> fileMap = new LinkedHashMap<>();
			String mcdir = server.getDataDirectory().getCanonicalFile().getAbsolutePath();

			Consumer<File> consumer = file0 -> {
				for (File file : FileUtils.listTree(file0))
				{
					String s1 = file.getAbsolutePath().replace(mcdir, "");

					if (s1.startsWith(File.separator))
					{
						s1 = s1.substring(File.separator.length());
					}

					fileMap.put(file, "_extra_" + File.separator + s1);
				}
			};

			for (String s : FTBUtilitiesConfig.backups.extra_files)
			{
				consumer.accept(new File(s));
			}

			new BackupEvent.Pre(consumer).post();

			for (File file : FileUtils.listTree(src))
			{
				String filePath = file.getAbsolutePath();
				fileMap.put(file, src.getName() + File.separator + filePath.substring(src.getAbsolutePath().length() + 1, filePath.length()));
			}

			totalFiles = fileMap.size();
			FTBUtilities.LOGGER.info("Backing up " + totalFiles + " files...");

			if (FTBUtilitiesConfig.backups.compression_level > 0)
			{
				out.append(".zip");
				dstFile = FileUtils.newFile(new File(backupsFolder, out.toString()));

				long start = System.currentTimeMillis();

				ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dstFile));
				zos.setLevel(FTBUtilitiesConfig.backups.compression_level);

				byte[] buffer = new byte[4096];

				FTBUtilities.LOGGER.info("Compressing " + totalFiles + " files...");

				currentFile = 0;
				for (Map.Entry<File, String> entry : fileMap.entrySet())
				{
					try
					{
						ZipEntry ze = new ZipEntry(entry.getValue());
						currentFileName = entry.getValue();

						zos.putNextEntry(ze);
						FileInputStream fis = new FileInputStream(entry.getKey());

						int len;
						while ((len = fis.read(buffer)) > 0)
						{
							zos.write(buffer, 0, len);
						}
						zos.closeEntry();
						fis.close();
					}
					catch (Exception ex)
					{
						ex.printStackTrace();
					}

					currentFile++;
				}

				zos.close();

				FTBUtilities.LOGGER.info("Done compressing in " + StringUtils.getTimeString(System.currentTimeMillis() - start) + " seconds (" + FileUtils.getSizeString(dstFile) + ")!");
			}
			else
			{
				dstFile = new File(new File(backupsFolder, out.toString()), src.getName());
				dstFile.mkdirs();

				currentFile = 0;
				for (Map.Entry<File, String> entry : fileMap.entrySet())
				{
					try
					{
						File file = entry.getKey();
						currentFileName = entry.getValue();
						File dst1 = new File(dstFile, entry.getValue());
						FileUtils.copyFile(file, dst1);
					}
					catch (Exception ex)
					{
						ex.printStackTrace();
					}

					currentFile++;
				}
			}

			FTBUtilities.LOGGER.info("Created " + dstFile.getAbsolutePath() + " from " + src.getAbsolutePath());
			success = true;

			if (!FTBUtilitiesConfig.backups.silent)
			{
				if (FTBUtilitiesConfig.backups.display_file_size)
				{
					String sizeB = FileUtils.getSizeString(dstFile);
					String sizeT = FileUtils.getSizeString(backupsFolder);
					notifyAll(server, player -> FTBUtilities.lang(player, "ftbutilities.lang.backup.end_2", StringUtils.getTimeString(System.currentTimeMillis() - time.getTimeInMillis()), (sizeB.equals(sizeT) ? sizeB : (sizeB + " | " + sizeT))), false);
				}
				else
				{
					notifyAll(server, player -> FTBUtilities.lang(player, "ftbutilities.lang.backup.end_1", StringUtils.getTimeString(System.currentTimeMillis() - time.getTimeInMillis())), false);
				}
			}
		}
		catch (Exception ex)
		{
			if (!FTBUtilitiesConfig.backups.silent)
			{
				notifyAll(server, player -> FTBUtilities.lang(player, "ftbutilities.lang.backup.fail", ex.getClass().getName()), true);
			}

			ex.printStackTrace();
			error = ex;
		}

		if (error != null)
		{
			if (dstFile != null)
			{
				FileUtils.delete(dstFile);
			}
		}
		else
		{
			totalSize = FileUtils.getSize(dstFile);
		}

		Backup backup = new Backup(time.getTimeInMillis(), out.toString().replace('\\', '/'), getLastIndex() + 1, success, totalSize);
		backups.add(backup);
		new BackupEvent.Post(backup, error).post();
		cleanupAndSave(totalSize);
		doingBackup = 2;
	}

	private void appendNum(StringBuilder sb, int num, char c)
	{
		if (num < 10)
		{
			sb.append('0');
		}
		sb.append(num);
		if (c != '\0')
		{
			sb.append(c);
		}
	}

	public long cleanupAndSave(long fileSize)
	{
		long totalSize = 0L;

		if (!backups.isEmpty())
		{
			backups.sort(null);

			for (Backup backup : backups)
			{
				totalSize += backup.size;
			}

			if (fileSize > 0L)
			{
				long freeSpace = backupsFolder.getFreeSpace();

				while (totalSize + fileSize > freeSpace && !backups.isEmpty())
				{
					Backup backup = backups.get(0);
					totalSize -= backup.size;
					FTBUtilities.LOGGER.info("Deleting old backup: " + backup.fileId);
					FileUtils.delete(backup.getFile());
				}
			}

			int backupsToKeep = FTBUtilitiesConfig.backups.backups_to_keep;

			if (backupsToKeep > 0)
			{
				if (backups.size() > backupsToKeep)
				{
					int toDelete = backups.size() - backupsToKeep;

					if (toDelete > 0)
					{
						for (int i = toDelete - 1; i >= 0; i--)
						{
							Backup b = backups.get(i);

							backups.remove(i);
						}
					}
				}

				backups.removeIf(backup -> !backup.getFile().exists());
			}
		}

		JsonArray array = new JsonArray();

		for (Backup backup : backups)
		{
			array.add(backup.toJsonObject());
		}

		JsonUtils.toJsonSafe(new File(backupsFolder, "backups.json"), array);
		return totalSize;
	}

	private int getLastIndex()
	{
		int i = 0;

		for (Backup b : backups)
		{
			i = Math.max(i, b.index);
		}

		return i;
	}
}