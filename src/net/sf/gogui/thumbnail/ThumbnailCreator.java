//----------------------------------------------------------------------------
// $Id$
//----------------------------------------------------------------------------

package net.sf.gogui.thumbnail;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import net.sf.gogui.game.GameInformation;
import net.sf.gogui.game.GameTree;
import net.sf.gogui.game.NodeUtil;
import net.sf.gogui.go.Board;
import net.sf.gogui.go.GoColor;
import net.sf.gogui.go.GoPoint;
import net.sf.gogui.go.Move;
import net.sf.gogui.gui.GuiField;
import net.sf.gogui.gui.BoardDrawer;
import net.sf.gogui.gui.GuiUtil;
import net.sf.gogui.sgf.SgfReader;
import net.sf.gogui.util.FileUtil;
import net.sf.gogui.version.Version;

/** Thumbnail creator.
    Creates thumbnails according to the freedesktop.org standard.
    @todo Save to temp file and rename as required by the standard.
*/
public final class ThumbnailCreator
{
    public ThumbnailCreator(boolean verbose)
    {
        m_verbose = verbose;
        m_drawer = new BoardDrawer();
    }

    public boolean create(File input)
    {
        return create(input, null, 128, true);
    }

    /** Create thumbnail.
        @param input The SGF file
        @param output The output thumbnail. Null for standard filename in
        .thumbnails/normal
        @param thumbnailSize The image size of the thumbnail.
        @param scale If true thumbnailSize will be scaled down for boards
        smaller than 19.
    */
    public boolean create(File input, File output, int thumbnailSize,
                          boolean scale)
    {
        assert(thumbnailSize > 0);
        m_lastThumbnail = null;
        m_lastError = "";
        try
        {
            log("File: " + input);
            URI uri = FileUtil.getURI(input);
            if (uri == null)
            {
                m_lastError = "Invalid file name";
                return false;
            }
            log("URI: " + uri);
            String md5 = getMD5(uri.toString());
            if (m_verbose)
                log("MD5: " + md5);
            ArrayList moves = new ArrayList();
            m_description = "";
            Board board = readFile(input, moves);
            for (int i = 0; i < moves.size(); ++i)
                board.play((Move)moves.get(i));
            int size = board.getSize();
            GuiField[][] field = new GuiField[size][size];
            for (int x = 0; x < size; ++x)
                for (int y = 0; y < size; ++y)
                {
                    field[x][y] = new GuiField();
                    GoColor color = board.getColor(GoPoint.get(x, y));
                    field[x][y].setColor(color);
                }
            int imageSize = thumbnailSize;
            if (scale)
                imageSize = Math.min(thumbnailSize * size / 19,
                                     thumbnailSize);
            BufferedImage image;
            if (imageSize < 256)
            {
                // Create large image and scale down, looks better than
                // creating small image            
                image = getImage(field, 2 * imageSize, 2 * imageSize);
                BufferedImage newImage = createImage(imageSize, imageSize);
                Graphics2D graphics = newImage.createGraphics();
                Image scaledInstance
                    = image.getScaledInstance(imageSize, imageSize,
                                              Image.SCALE_SMOOTH);
                graphics.drawImage(scaledInstance, 0, 0, null);
                image = newImage;
            }
            else
                image = getImage(field, imageSize, imageSize);

            if (output == null)
                output = new File(ThumbnailPlatform.getNormalDir(),
                                  md5 + ".png");

            long lastModified = input.lastModified() / 1000L;
            if (lastModified == 0L)
            {
                System.err.println("Could not get last modification time: "
                                   + input);
                return false;
            }
            writeImage(image, output, uri, lastModified);

            return true;
        }
        catch (FileNotFoundException e)
        {
            m_lastError = "File not found: " + input;
            log(m_lastError);
        }
        catch (IOException e)
        {
            m_lastError = e.getMessage();
            log(m_lastError);
        }
        catch (NoSuchAlgorithmException e)
        {
            m_lastError = "No MD5 message digest found";
            log(m_lastError);
        }
        catch (SgfReader.SgfError e)
        {
            m_lastError = e.getMessage();
            log(m_lastError);
        }
        return false;
    }

    public String getLastDescription()
    {
        return m_description;
    }

    public String getLastError()
    {
        return m_lastError;
    }

    public File getLastThumbnail()
    {
        return m_lastThumbnail;
    }

    private final boolean m_verbose;

    private String m_description;

    private String m_lastError;

    private File m_lastThumbnail;

    private final BoardDrawer m_drawer;

    private BufferedImage createImage(int width, int height)
    {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private void addMeta(org.w3c.dom.Node node, String keyword, String value)
    {
        IIOMetadataNode text = new IIOMetadataNode("Text");
        IIOMetadataNode textEntry = new IIOMetadataNode("TextEntry");
        textEntry.setAttribute("value", value);
        textEntry.setAttribute("keyword", keyword);
        textEntry.setAttribute("encoding", Locale.getDefault().toString());
        textEntry.setAttribute("language", "en");
        textEntry.setAttribute("compression", "none");
        text.appendChild(textEntry);
        node.appendChild(text);
    }

    private Board readFile(File file, ArrayList moves)
        throws FileNotFoundException, SgfReader.SgfError
    {
        FileInputStream in = new FileInputStream(file);
        SgfReader reader;
        try
        {
            reader = new SgfReader(in, file.toString(), null, 0);
        }
        finally
        {
            try
            {
                in.close();
            }
            catch (IOException e)
            {
                log(e.getMessage());
            }
        }
        GameTree tree = reader.getGameTree();
        GameInformation gameInformation = tree.getGameInformation();
        int size = gameInformation.m_boardSize;
        m_description = gameInformation.suggestGameName();
        if (m_description == null)
            m_description = "";
        Board board = new Board(size);
        net.sf.gogui.game.Node node = tree.getRoot();
        ArrayList nodeMoves = new ArrayList();
        while (node != null)
        {
            NodeUtil.getAllAsMoves(node, nodeMoves);
            moves.addAll(nodeMoves);
            if (node.getNumberAddBlack() > 0 && node.getNumberAddWhite() > 0)
                break;
            node = node.getChild();
        }
        //if (m_verbose)
        //    BoardUtil.print(board, System.err);
        return board;
    }

    private BufferedImage getImage(GuiField[][] field, int width, int height)
    {
        BufferedImage image = createImage(width, height);
        Graphics2D graphics = image.createGraphics();
        GuiUtil.setAntiAlias(graphics);
        m_drawer.draw(graphics, field, width, false);
        graphics.dispose();
        return image;
    }

    private static String getMD5(String string)
        throws NoSuchAlgorithmException, UnsupportedEncodingException
    {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] md5 = digest.digest(string.getBytes("US-ASCII"));
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < md5.length; ++i)
        {
            buffer.append(Integer.toHexString((md5[i] >> 4) & 0x0F));
            buffer.append(Integer.toHexString(md5[i] & 0x0F));
        }
        return buffer.toString();
    }

    private void log(String line)
    {
        if (! m_verbose)
            return;
        System.err.println(line);
    }

    private void writeImage(BufferedImage image, File file, URI uri,
                            long lastModified)
        throws IOException
    {
        Iterator iter = ImageIO.getImageWritersBySuffix("png");
        ImageWriter writer = (ImageWriter)iter.next();
        ImageTypeSpecifier specifier = new ImageTypeSpecifier(image);
        IIOMetadata meta = writer.getDefaultImageMetadata(specifier, null);
        String formatName = "javax_imageio_1.0";
        org.w3c.dom.Node node = meta.getAsTree(formatName);
        addMeta(node, "Thumb::URI", uri.toString());
        addMeta(node, "Thumb::MTime", Long.toString(lastModified));
        addMeta(node, "Thumb::Mimetype", "application/x-go-sgf");
        if (! m_description.equals(""))
            addMeta(node, "Description", m_description);
        addMeta(node, "Software", "GoGui " + Version.get());
        try
        {
            meta.mergeTree(formatName, node);
        }
        catch (IIOInvalidTreeException e)
        {
            assert(false);
            return;
        }
        ImageOutputStream ios = ImageIO.createImageOutputStream(file);
        writer.setOutput(ios);
        writer.write(null, new IIOImage(image, null, meta), null);
        m_lastThumbnail = file;
    }
}
